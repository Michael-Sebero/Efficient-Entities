package com.michaelsebero.efficiententities.renderer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.michaelsebero.efficiententities.EfficientEntities;
import com.michaelsebero.efficiententities.opengl.GpuSync;
import com.michaelsebero.efficiententities.opengl.PersistentBuffer;
import com.michaelsebero.efficiententities.util.CubeGeometry;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;

/**
 * Core renderer for Efficient Entities.
 *
 * <h3>Vertex layout (32 bytes / 8 floats)</h3>
 * XYZ (eye-space position) | UV | NxNyNz (eye-space normal, normalised)
 *
 * <h3>Batch tracking</h3>
 * The ASM transformer wraps entity model render() calls with startBatch/endBatch.
 * However some render paths (e.g. first-person hand, armor) call ModelRenderer.render()
 * directly without going through the entity model render() method, so they are never
 * wrapped by the transformer. We handle these with {@link #inBatch}: if render() is
 * called outside a batch we auto-wrap that single call so the geometry still reaches
 * the screen.
 */
public class EfficientModelRenderer {

    // ------------------------------------------------------------------ Constants

    private static final int   FLOATS_PER_VERTEX   = 8;
    private static final int   BYTES_PER_VERTEX    = FLOATS_PER_VERTEX * Float.BYTES; // 32
    private static final int   VERTICES_PER_CUBE   = 24;
    private static final int   BYTES_PER_CUBE      = VERTICES_PER_CUBE * BYTES_PER_VERTEX;
    private static final int   MAX_CUBES_PER_SLICE = 4096;
    private static final int   BUFFER_SLICES       = 3;
    private static final long  SLICE_SIZE          = (long) MAX_CUBES_PER_SLICE * BYTES_PER_CUBE;
    private static final long  BUFFER_SIZE         = SLICE_SIZE * BUFFER_SLICES;

    private static final int MAP_FLAGS   = GL30.GL_MAP_WRITE_BIT
                                         | GL44.GL_MAP_PERSISTENT_BIT
                                         | GL44.GL_MAP_COHERENT_BIT;
    private static final int STORE_FLAGS = MAP_FLAGS;

    private static final float RAD_TO_DEG = (float)(180.0 / Math.PI);

    // How often (in rendered frames) to log a throughput summary.
    private static final int LOG_INTERVAL_FRAMES = 200;

    // ------------------------------------------------------------------ Singleton

    private static EfficientModelRenderer INSTANCE;

    public static EfficientModelRenderer instance() {
        if (INSTANCE == null) INSTANCE = new EfficientModelRenderer();
        return INSTANCE;
    }

    // ------------------------------------------------------------------ GL state

    private boolean initialised  = false;
    private int     vbo;

    private ByteBuffer   mappedBuffer;
    private final Object[] sliceSyncs = new Object[BUFFER_SLICES];

    private int currentSlice = 0;
    private int vertexCount  = 0;
    private int batchStart   = 0;

    /** True while we are between a startBatch / endBatch pair. */
    private boolean inBatch = false;

    private final FloatBuffer matrixBuf = BufferUtils.createFloatBuffer(16);

    // ------------------------------------------------------------------ Diagnostics

    private int frameCount       = 0;
    private int batchesThisPeriod = 0;
    private int cubesThisPeriod   = 0;
    private int autoWrapsThisPeriod = 0; // render() calls outside a batch (e.g. hand)

    // ------------------------------------------------------------------ Lifecycle

    private void ensureInitialised() {
        if (initialised) return;
        if (!PersistentBuffer.isAvailable()) {
            EfficientEntities.LOG.warn("[EfficientEntities] Persistent buffer storage unavailable – falling back to vanilla rendering.");
            return;
        }
        if (!GpuSync.isAvailable()) {
            EfficientEntities.LOG.warn("[EfficientEntities] GPU sync unavailable – falling back to vanilla rendering.");
            return;
        }

        vbo = PersistentBuffer.createBuffer();
        PersistentBuffer.bindBuffer(GL15.GL_ARRAY_BUFFER, vbo, true);
        PersistentBuffer.initStorage(GL15.GL_ARRAY_BUFFER, vbo, BUFFER_SIZE, STORE_FLAGS);
        mappedBuffer = PersistentBuffer.mapBuffer(GL15.GL_ARRAY_BUFFER, vbo, 0, BUFFER_SIZE, MAP_FLAGS);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        initialised = true;
        EfficientEntities.LOG.info("[EfficientEntities] Renderer initialised. VBO: {} KB x {} slices = {} KB total.",
            SLICE_SIZE / 1024, BUFFER_SLICES, BUFFER_SIZE / 1024);
    }

    // ------------------------------------------------------------------ Frame lifecycle

    public void beginFrame() {
        ensureInitialised();
        if (!initialised) return;

        currentSlice = (currentSlice + 1) % BUFFER_SLICES;
        vertexCount  = 0;
        batchStart   = 0;
        inBatch      = false;

        Object sync = sliceSyncs[currentSlice];
        if (sync != null) {
            GpuSync.waitSync(sync);
            GpuSync.deleteSync(sync);
            sliceSyncs[currentSlice] = null;
        }

        // Periodic summary log.
        frameCount++;
        if (frameCount % LOG_INTERVAL_FRAMES == 0) {
            EfficientEntities.LOG.info(
                "[EfficientEntities] Last {} frames: {} batches drawn, {} cubes batched, {} auto-wrapped calls (hand/armor).",
                LOG_INTERVAL_FRAMES, batchesThisPeriod, cubesThisPeriod, autoWrapsThisPeriod);
            batchesThisPeriod   = 0;
            cubesThisPeriod     = 0;
            autoWrapsThisPeriod = 0;
        }
    }

    public void finishFrame() {
        if (!initialised) return;
        if (sliceSyncs[currentSlice] == null) {
            sliceSyncs[currentSlice] = GpuSync.createSync();
        }
    }

    // ------------------------------------------------------------------ Batch lifecycle

    public void startBatch() {
        if (!initialised) return;
        batchStart = vertexCount;
        inBatch    = true;
    }

    public void endBatch() {
        if (!initialised) return;
        inBatch = false;
        flushRange(batchStart, vertexCount);
    }

    /**
     * Draws vertices in the range [from, to) from the mapped VBO using the
     * legacy fixed-function client arrays so texturing and lighting work.
     */
    private void flushRange(int from, int to) {
        int count = to - from;
        if (count <= 0) return;

        long baseOffset = sliceOffset() + (long) from * BYTES_PER_VERTEX;

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, baseOffset);

        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, baseOffset + 12);

        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glNormalPointer(GL11.GL_FLOAT, BYTES_PER_VERTEX, baseOffset + 20);

        // Vertices are already in eye-space — draw without any additional transform.
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
        GL11.glPopMatrix();

        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        batchesThisPeriod++;
    }

    // ------------------------------------------------------------------ Per-part render

    /**
     * Replaces {@code ModelRenderer.render(float scale)}.
     *
     * <p>If called inside a batch (ASM-injected entity model render), we accumulate
     * geometry into the VBO for the batch draw. If called <em>outside</em> a batch
     * (e.g. first-person hand, armor renderer) we auto-wrap this single part —
     * mark a start, emit, then immediately flush — so it is never invisible.
     */
    @SuppressWarnings("unchecked")
    public void render(ModelRenderer renderer, float scale) {
        if (!initialised || renderer.isHidden || !renderer.showModel) return;

        boolean autoWrap = !inBatch;
        if (autoWrap) {
            // Called outside a batch (hand, armor, etc.) — wrap this part alone.
            batchStart = vertexCount;
            inBatch    = true;
            autoWrapsThisPeriod++;
        }

        boolean hasOffset = renderer.offsetX != 0f
                         || renderer.offsetY != 0f
                         || renderer.offsetZ != 0f;
        if (hasOffset) {
            GlStateManager.translate(renderer.offsetX, renderer.offsetY, renderer.offsetZ);
        }

        boolean hasTransform = renderer.rotationPointX != 0f
                            || renderer.rotationPointY != 0f
                            || renderer.rotationPointZ != 0f
                            || renderer.rotateAngleX   != 0f
                            || renderer.rotateAngleY   != 0f
                            || renderer.rotateAngleZ   != 0f;

        if (hasTransform) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(
                renderer.rotationPointX * scale,
                renderer.rotationPointY * scale,
                renderer.rotationPointZ * scale);
            if (renderer.rotateAngleZ != 0f)
                GlStateManager.rotate(renderer.rotateAngleZ * RAD_TO_DEG, 0f, 0f, 1f);
            if (renderer.rotateAngleY != 0f)
                GlStateManager.rotate(renderer.rotateAngleY * RAD_TO_DEG, 0f, 1f, 0f);
            if (renderer.rotateAngleX != 0f)
                GlStateManager.rotate(renderer.rotateAngleX * RAD_TO_DEG, 1f, 0f, 0f);
        }

        // Read modelview matrix NOW — after all per-part transforms.
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrixBuf);
        float m00 = matrixBuf.get(0),  m01 = matrixBuf.get(1),  m02 = matrixBuf.get(2);
        float m10 = matrixBuf.get(4),  m11 = matrixBuf.get(5),  m12 = matrixBuf.get(6);
        float m20 = matrixBuf.get(8),  m21 = matrixBuf.get(9),  m22 = matrixBuf.get(10);
        float m30 = matrixBuf.get(12), m31 = matrixBuf.get(13), m32 = matrixBuf.get(14);

        List<ModelBox> cubeList = renderer.cubeList;
        if (cubeList != null) {
            for (int i = 0, n = cubeList.size(); i < n; i++) {
                ModelBox box = cubeList.get(i);
                CubeGeometry geo = ((Supplier<CubeGeometry>) box).get();
                if (geo != null) {
                    emitCube(geo, m00, m01, m02, m10, m11, m12,
                                  m20, m21, m22, m30, m31, m32, scale);
                }
            }
        }

        List<ModelRenderer> children = renderer.childModels;
        if (children != null) {
            for (int i = 0, n = children.size(); i < n; i++) {
                render(children.get(i), scale);
            }
        }

        if (hasTransform) GlStateManager.popMatrix();
        if (hasOffset) {
            GlStateManager.translate(-renderer.offsetX, -renderer.offsetY, -renderer.offsetZ);
        }

        if (autoWrap) {
            inBatch = false;
            flushRange(batchStart, vertexCount);
        }
    }

    // ------------------------------------------------------------------ Geometry helpers

    private void emitCube(CubeGeometry g,
                           float m00, float m01, float m02,
                           float m10, float m11, float m12,
                           float m20, float m21, float m22,
                           float m30, float m31, float m32,
                           float s) {
        if (vertexCount + VERTICES_PER_CUBE > MAX_CUBES_PER_SLICE * VERTICES_PER_CUBE) {
            EfficientEntities.LOG.warn("[EfficientEntities] VBO slice overflow; skipping cube.");
            return;
        }

        float x0 = g.x0*s, y0 = g.y0*s, z0 = g.z0*s;
        float x1 = g.x1*s, y1 = g.y1*s, z1 = g.z1*s;

        emitQuad(x1,y1,z1, g.upx0,g.vpx1,  x1,y0,z1, g.upx0,g.vpx0,
                 x1,y0,z0, g.upx1,g.vpx0,  x1,y1,z0, g.upx1,g.vpx1,
                 1f,0f,0f,  m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitQuad(x0,y1,z0, g.unx0,g.vnx1,  x0,y0,z0, g.unx0,g.vnx0,
                 x0,y0,z1, g.unx1,g.vnx0,  x0,y1,z1, g.unx1,g.vnx1,
                 -1f,0f,0f, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitQuad(x0,y1,z0, g.upy0,g.vpy1,  x1,y1,z0, g.upy1,g.vpy1,
                 x1,y1,z1, g.upy1,g.vpy0,  x0,y1,z1, g.upy0,g.vpy0,
                 0f,1f,0f,  m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitQuad(x0,y0,z1, g.uny0,g.vny0,  x1,y0,z1, g.uny1,g.vny0,
                 x1,y0,z0, g.uny1,g.vny1,  x0,y0,z0, g.uny0,g.vny1,
                 0f,-1f,0f, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitQuad(x0,y1,z1, g.upz0,g.vpz1,  x0,y0,z1, g.upz0,g.vpz0,
                 x1,y0,z1, g.upz1,g.vpz0,  x1,y1,z1, g.upz1,g.vpz1,
                 0f,0f,1f,  m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitQuad(x1,y1,z0, g.unz0,g.vnz1,  x1,y0,z0, g.unz0,g.vnz0,
                 x0,y0,z0, g.unz1,g.vnz0,  x0,y1,z0, g.unz1,g.vnz1,
                 0f,0f,-1f, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);

        cubesThisPeriod++;
    }

    private void emitQuad(float lx0, float ly0, float lz0, float u0, float v0,
                           float lx1, float ly1, float lz1, float u1, float v1,
                           float lx2, float ly2, float lz2, float u2, float v2,
                           float lx3, float ly3, float lz3, float u3, float v3,
                           float nx,  float ny,  float nz,
                           float m00, float m01, float m02,
                           float m10, float m11, float m12,
                           float m20, float m21, float m22,
                           float m30, float m31, float m32) {

        float wnx = nx*m00 + ny*m10 + nz*m20;
        float wny = nx*m01 + ny*m11 + nz*m21;
        float wnz = nx*m02 + ny*m12 + nz*m22;
        float len = (float) Math.sqrt(wnx*wnx + wny*wny + wnz*wnz);
        if (len > 1e-6f) { wnx /= len; wny /= len; wnz /= len; }

        emitVertex(lx0,ly0,lz0, u0,v0, wnx,wny,wnz, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitVertex(lx1,ly1,lz1, u1,v1, wnx,wny,wnz, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitVertex(lx2,ly2,lz2, u2,v2, wnx,wny,wnz, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
        emitVertex(lx3,ly3,lz3, u3,v3, wnx,wny,wnz, m00,m01,m02, m10,m11,m12, m20,m21,m22, m30,m31,m32);
    }

    private void emitVertex(float lx, float ly, float lz, float u, float v,
                             float nx, float ny, float nz,
                             float m00, float m01, float m02,
                             float m10, float m11, float m12,
                             float m20, float m21, float m22,
                             float m30, float m31, float m32) {

        float wx = lx*m00 + ly*m10 + lz*m20 + m30;
        float wy = lx*m01 + ly*m11 + lz*m21 + m31;
        float wz = lx*m02 + ly*m12 + lz*m22 + m32;

        int pos = (int)(sliceOffset() + (long) vertexCount * BYTES_PER_VERTEX);
        mappedBuffer.position(pos);
        mappedBuffer.putFloat(wx);
        mappedBuffer.putFloat(wy);
        mappedBuffer.putFloat(wz);
        mappedBuffer.putFloat(u);
        mappedBuffer.putFloat(v);
        mappedBuffer.putFloat(nx);
        mappedBuffer.putFloat(ny);
        mappedBuffer.putFloat(nz);

        vertexCount++;
    }

    private long sliceOffset() {
        return (long) currentSlice * SLICE_SIZE;
    }
}
