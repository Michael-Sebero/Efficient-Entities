package com.michaelsebero.efficiententities.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import com.michaelsebero.efficiententities.EEConfig;
import com.michaelsebero.efficiententities.EfficientEntities;
import com.michaelsebero.efficiententities.mixin.AccessorTexturedQuad;
import com.michaelsebero.efficiententities.opengl.GlCapabilities;
import com.michaelsebero.efficiententities.opengl.GpuFence;
import com.michaelsebero.efficiententities.opengl.PersistentBuffer;
import com.michaelsebero.efficiententities.opengl.VaoHelper;
import com.michaelsebero.efficiententities.util.MatrixStack;
import com.michaelsebero.efficiententities.util.SimpleStack;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;

/**
 * Core renderer for Efficient Entities.
 *
 * <h3>Vertex layout — 24 bytes per vertex</h3>
 * <pre>
 *  bytes  0–11 : XYZ position        (3 × GL_FLOAT = 12 bytes)
 *  bytes 12–19 : UV texture coords   (2 × GL_FLOAT =  8 bytes)
 *  bytes 20–22 : XYZ normal          (3 × GL_BYTE  =  3 bytes, packed into a 4-byte int)
 *  byte     23 : padding             (0)
 * </pre>
 * This is vanilla's {@code OLDMODEL_POSITION_TEX_NORMAL} format, so the output
 * of a part matches its display list byte for byte.
 *
 * <h3>Coordinate space</h3>
 * Vertices are baked in <em>batch space</em>: only the bone-hierarchy
 * transforms accumulated in {@link #matrix} since the batch began are applied.
 * The GL modelview, which still holds the transform current when the batch
 * began, completes the transform when the batch is drawn.  Baked parts
 * therefore issue no GL matrix calls at all; that, plus one draw call per
 * batch instead of one display list call per part, is where the time is saved.
 *
 * <h3>Batches</h3>
 * {@code EfficientEntitiesTransformer} wraps each run of consecutive part
 * renders in a model's {@code render} method with {@link #startBatch()} /
 * {@link #endBatch()}.  A part rendered outside such a run becomes its own
 * batch, drawn as soon as its subtree is baked.
 *
 * <h3>Vanilla scopes</h3>
 * Parts that cannot be baked (a subclass overriding {@code render}, a box
 * subclass with its own {@code render}, chest renderers, everything while
 * OptiFine is installed) run vanilla's own code.  Pending vertices are drawn
 * first so draw order and GL state match vanilla exactly; a baked parent's
 * accumulated transform is loaded into GL first so the vanilla subtree lands
 * in the same place.  While {@link #vanillaDepth} is non-zero every
 * part, including descendants, renders vanilla.
 *
 * <h3>Buffer management</h3>
 * Triple-buffered persistent VBOs with explicit-flush / unsynchronised mapping,
 * fenced once per frame.  When there is no persistent buffer support, a
 * client-side vertex array is used instead.  Vertex data is written with
 * absolute {@link ByteBuffer} puts rather than {@code sun.misc.Unsafe}, whose
 * memory-access methods Java 24+ deprecates for removal and which Cleanroom
 * (Java 21+) would otherwise warn about.
 *
 * <h3>Compatibility</h3>
 * Only LWJGL calls with identical signatures in LWJGL 2 and LWJGL 3 are used
 * (enforced by {@code LwjglApiCompatibilityTest}), so no Cleanroom compatibility
 * layer is needed.  Anything that still fails — a missing GL entry point, a
 * driver error, mixins that did not apply — disables the renderer and leaves
 * vanilla rendering in place instead of crashing the game.
 */
public final class EfficientModelRenderer {

    // ------------------------------------------------------------------ Constants

    private static final int BYTES_PER_VERTEX = 24;
    /** Initial bytes per slice: room for 4096 ordinary boxes. */
    private static final int INITIAL_CAPACITY = 4096 * 24 * BYTES_PER_VERTEX;
    private static final int BUFFER_SLICES    = 3;

    /** {@code glBufferStorage} flags: CPU-written, persistently mapped. */
    private static final int STORAGE_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT;

    /**
     * {@code glMapBufferRange} flags.  Unsynchronised because {@link GpuFence}
     * guards slice reuse; explicit-flush because each draw flushes exactly the
     * range it reads.
     */
    private static final int MAP_FLAGS = STORAGE_FLAGS | GL30.GL_MAP_FLUSH_EXPLICIT_BIT | GL30.GL_MAP_UNSYNCHRONIZED_BIT;

    /** {@link ModelPartExtension} state for a part that must render vanilla. */
    private static final int UNSUPPORTED = -1;

    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    /** OptiFine's shaders and CEM both replace entity model rendering; leave it to them. */
    private static final boolean OPTIFINE = isOptiFinePresent();

    private static final ClassValue<Boolean> OVERRIDES_PART_RENDER =
        overrides(ModelRenderer.class, new Class<?>[] { float.class }, "render", "func_78785_a");
    private static final ClassValue<Boolean> OVERRIDES_BOX_RENDER =
        overrides(ModelBox.class, new Class<?>[] { BufferBuilder.class, float.class }, "render", "func_178780_a");
    private static final ClassValue<Boolean> CHEST_RENDERERS = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getSimpleName().endsWith("ChestRenderer");
        }
    };

    private static final EfficientModelRenderer INSTANCE = new EfficientModelRenderer();

    // ------------------------------------------------------------------ State

    private boolean initialised;
    /** Recomputed every frame; when false every call falls through to vanilla. */
    private boolean active;
    /** Set once anything fails (GL entry point, driver, missing mixins); vanilla renders for the rest of the session. */
    private boolean failed;
    private Thread  renderThread;

    // Buffers
    /** {@code true} = persistent VBO slices; {@code false} = client-side vertex array. */
    private boolean persistent;
    private boolean useVao;
    /** Capacity in bytes of each slice (or of the client-side buffer). */
    private int capacity;
    private final int[]        vbos    = new int[BUFFER_SLICES];
    private final int[]        vaos    = new int[BUFFER_SLICES];
    private final ByteBuffer[] mapped  = new ByteBuffer[BUFFER_SLICES];
    /** Timer query marking the end of the frame that last wrote each slice; 0 = none. */
    private final int[]        fences  = new int[BUFFER_SLICES];
    /** Deletions of outgrown slices, run once the slice's fence shows the GPU is done with them. */
    @SuppressWarnings("unchecked")
    private final SimpleStack<Runnable>[] pendingDeletes = new SimpleStack[BUFFER_SLICES];
    private int slice;
    /** Current write target: this frame's mapped slice, or the client-side buffer. */
    private ByteBuffer buffer;

    // Batch
    /** Vertices written to {@link #buffer} this frame. */
    private int     vertexCount;
    /** First vertex not yet drawn. */
    private int     batchStart;
    private boolean inBatch;
    private final MatrixStack matrix = new MatrixStack();
    /** Scratch for a box's transformed corners. */
    private float[] corners = new float[24 * 3];
    private final FloatBuffer glMatrix = GLAllocation.createDirectFloatBuffer(16);

    // Vanilla scopes
    private int vanillaDepth;
    /** Top-level parts that opened a vanilla scope, closed by their RETURN hook. */
    private ModelRenderer[] vanillaRoots = new ModelRenderer[8];
    private int vanillaRootCount;

    // Statistics
    private int frames, draws, boxes, autoBatches, vanillaParts;

    private EfficientModelRenderer() {
        for (int i = 0; i < BUFFER_SLICES; i++) pendingDeletes[i] = new SimpleStack<>();
    }

    // ------------------------------------------------------------------ Entry points

    /** {@code RenderTickEvent} START. */
    public static void beginFrame() {
        INSTANCE.onBeginFrame();
    }

    /** {@code RenderTickEvent} END. */
    public static void finishFrame() {
        INSTANCE.onFinishFrame();
    }

    /**
     * {@code ModelRenderer.render(float)} HEAD.
     *
     * @return {@code true} if the part was handled and vanilla must be skipped
     */
    public static boolean renderPart(ModelRenderer part, float scale) {
        EfficientModelRenderer r = INSTANCE;
        return r.active && r.vanillaDepth == 0 && Thread.currentThread() == r.renderThread && r.render(part, scale);
    }

    /** {@code ModelRenderer.render(float)} RETURN, reached only when vanilla ran. */
    public static void afterVanillaRender(ModelRenderer part) {
        EfficientModelRenderer r = INSTANCE;
        int n = r.vanillaRootCount;
        if (n > 0 && r.vanillaRoots[n - 1] == part) {
            r.vanillaRoots[--r.vanillaRootCount] = null;
            r.vanillaDepth--;
        }
    }

    /** Injected before a run of part renders. */
    public static void startBatch() {
        EfficientModelRenderer r = INSTANCE;
        if (r.active && r.vanillaDepth == 0 && Thread.currentThread() == r.renderThread) {
            // A batch left open by an exception would otherwise swallow this one.
            if (r.inBatch) r.closeBatch();
            r.openBatch();
        }
    }

    /** Injected after a run of part renders. */
    public static void endBatch() {
        EfficientModelRenderer r = INSTANCE;
        if (r.inBatch && r.vanillaDepth == 0 && Thread.currentThread() == r.renderThread) {
            r.closeBatch();
        }
    }

    /**
     * Injected before each part render inside a batched run.  A subclass that
     * overrides {@code render} may move the GL matrix before drawing anything,
     * so the batch is drawn and closed first; the part and whatever it reaches
     * then render against the live GL matrix.
     */
    public static void beforeBatchedPart(ModelRenderer part) {
        if (part.getClass() == ModelRenderer.class) return;
        EfficientModelRenderer r = INSTANCE;
        if (r.inBatch && r.vanillaDepth == 0 && Thread.currentThread() == r.renderThread
                && OVERRIDES_PART_RENDER.get(part.getClass())) {
            r.closeBatch();
        }
    }

    /** Everything rendered until the matching {@link #exitVanillaScope()} goes through vanilla. */
    public static void enterVanillaScope() {
        EfficientModelRenderer r = INSTANCE;
        if (r.active && Thread.currentThread() == r.renderThread) {
            r.flush();
            r.vanillaDepth++;
        }
    }

    public static void exitVanillaScope() {
        EfficientModelRenderer r = INSTANCE;
        if (r.active && r.vanillaDepth > 0 && Thread.currentThread() == r.renderThread) {
            r.vanillaDepth--;
        }
    }

    public static boolean isChestRenderer(Class<?> rendererClass) {
        return CHEST_RENDERERS.get(rendererClass);
    }

    // ------------------------------------------------------------------ Frame lifecycle

    private void onBeginFrame() {
        if (!initialised) initialise();

        // Recover from anything an exception left behind last frame.
        inBatch = false;
        vanillaDepth = 0;
        while (vanillaRootCount > 0) vanillaRoots[--vanillaRootCount] = null;

        active = !OPTIFINE && !failed && EEConfig.enabled;
        if (!active) return;

        if (persistent) {
            slice = (slice + 1) % BUFFER_SLICES;
            try {
                // Wait for the GPU to finish reading this slice before overwriting it.
                int fence = fences[slice];
                if (fence != 0) {
                    fences[slice] = 0;
                    GpuFence.waitAndDelete(fence);
                }
                SimpleStack<Runnable> deletes = pendingDeletes[slice];
                while (!deletes.isEmpty()) deletes.pop().run();
            } catch (LinkageError | RuntimeException e) {
                disable("GPU synchronisation failed", e);
                return;
            }
            buffer = mapped[slice];
        }
        vertexCount = 0;
        batchStart  = 0;

        logStatistics();
    }

    private void onFinishFrame() {
        if (active && persistent && fences[slice] == 0) {
            try {
                fences[slice] = GpuFence.create();
            } catch (LinkageError | RuntimeException e) {
                disable("GPU synchronisation failed", e);
            }
        }
    }

    private void initialise() {
        initialised  = true;
        renderThread = Thread.currentThread();

        if (OPTIFINE) {
            EfficientEntities.LOG.info("[EfficientEntities] OptiFine detected; entity models stay on its renderer.");
            return;
        }
        if (!ModelPartExtension.class.isAssignableFrom(ModelRenderer.class)
                || !ModelBoxExtension.class.isAssignableFrom(ModelBox.class)) {
            disable("Mixins were not applied to ModelRenderer/ModelBox (is MixinBooter installed?)", null);
            return;
        }

        try {
            GlCapabilities caps = GlCapabilities.query();
            PersistentBuffer.select(caps);
            GpuFence.select(caps);
            VaoHelper.select(caps);
            useVao   = VaoHelper.isAvailable();
            capacity = INITIAL_CAPACITY;

            persistent = PersistentBuffer.isAvailable() && GpuFence.isAvailable() && initialisePersistent();
            if (persistent) {
                EfficientEntities.LOG.info("[EfficientEntities] OpenGL {}: persistent VBO path, {} KB × {} slices.",
                    caps, capacity / 1024, BUFFER_SLICES);
            } else {
                buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
                EfficientEntities.LOG.info("[EfficientEntities] OpenGL {}: persistent buffers unavailable; using client-side vertex arrays.",
                    caps);
            }
        } catch (LinkageError | RuntimeException e) {
            disable("Could not initialise", e);
        }
    }

    /**
     * Allocates the slices and runs one fence round trip, so a GL entry point
     * missing from this LWJGL build surfaces here rather than mid-frame.
     */
    private boolean initialisePersistent() {
        try {
            if (!allocateSlices(capacity, vbos, vaos, mapped)) return false;
            GpuFence.waitAndDelete(GpuFence.create());
            return true;
        } catch (LinkageError | RuntimeException e) {
            EfficientEntities.LOG.warn("[EfficientEntities] Persistent buffer path failed ({}); using client-side vertex arrays.", e.toString());
            useVao = false;
            return false;
        }
    }

    // ------------------------------------------------------------------ Batching

    private void openBatch() {
        inBatch    = true;
        batchStart = vertexCount;
        matrix.reset();
    }

    private void closeBatch() {
        inBatch = false;
        flush();
    }

    /** Draws every vertex written since the last flush. */
    private void flush() {
        int count = vertexCount - batchStart;
        if (count > 0) draw(batchStart, count);
        if (persistent) {
            batchStart = vertexCount;
        } else {
            // Client-side arrays are copied by the driver at draw time.
            vertexCount = batchStart = 0;
        }
    }

    // ------------------------------------------------------------------ Part rendering

    private boolean render(ModelRenderer part, float scale) {
        if (part.isHidden || !part.showModel) return true;

        if (!isSupported(part)) {
            flush();
            pushVanillaRoot(part);
            vanillaParts++;
            return false;
        }

        boolean ownBatch = !inBatch;
        if (ownBatch) {
            openBatch();
            autoBatches++;
        }
        renderTree(part, scale);
        if (ownBatch) closeBatch();
        return true;
    }

    /** Bakes {@code part} and its subtree; mirrors vanilla's {@code ModelRenderer.render}. */
    private void renderTree(ModelRenderer part, float scale) {
        boolean translated = part.offsetX != 0f || part.offsetY != 0f || part.offsetZ != 0f
                          || part.rotationPointX != 0f || part.rotationPointY != 0f || part.rotationPointZ != 0f;
        boolean rotated    = part.rotateAngleX != 0f || part.rotateAngleY != 0f || part.rotateAngleZ != 0f;
        boolean pushed     = translated || rotated;

        if (pushed) {
            matrix.push();
            // Vanilla translates by the offset, then by the rotation point; one combined translate is equivalent.
            if (translated) {
                matrix.translate(part.offsetX + part.rotationPointX * scale,
                                 part.offsetY + part.rotationPointY * scale,
                                 part.offsetZ + part.rotationPointZ * scale);
            }
            if (part.rotateAngleZ != 0f) matrix.rotateZ(part.rotateAngleZ);
            if (part.rotateAngleY != 0f) matrix.rotateY(part.rotateAngleY);
            if (part.rotateAngleX != 0f) matrix.rotateX(part.rotateAngleX);
        }

        List<ModelBox> cubes = part.cubeList;
        for (int i = 0, n = cubes.size(); i < n; i++) {
            BoxGeometry geometry = geometry(cubes.get(i));
            if (geometry.supported) {
                emitBox(geometry, scale);
            } else {
                // The box list changed since validation; take the vanilla path from next frame.
                ((ModelPartExtension) part).efficiententities$setSupportState(UNSUPPORTED);
            }
        }

        List<ModelRenderer> children = part.childModels;
        if (children != null) {
            for (int i = 0, n = children.size(); i < n; i++) {
                ModelRenderer child = children.get(i);
                if (child.isHidden || !child.showModel) continue;
                if (isSupported(child)) {
                    renderTree(child, scale);
                } else {
                    renderVanillaChild(child, scale);
                }
            }
        }

        if (pushed) matrix.pop();
    }

    /** Hands a child to vanilla, positioned by the parent transform baked so far. */
    private void renderVanillaChild(ModelRenderer child, float scale) {
        flush();
        GlStateManager.pushMatrix();
        matrix.store(glMatrix);
        GlStateManager.multMatrix(glMatrix);
        vanillaDepth++;
        try {
            child.render(scale);
        } finally {
            vanillaDepth--;
            GlStateManager.popMatrix();
        }
        vanillaParts++;
    }

    private void pushVanillaRoot(ModelRenderer part) {
        if (vanillaRootCount == vanillaRoots.length) {
            vanillaRoots = Arrays.copyOf(vanillaRoots, vanillaRoots.length * 2);
        }
        vanillaRoots[vanillaRootCount++] = part;
        vanillaDepth++;
    }

    // ------------------------------------------------------------------ Support checks

    /**
     * Whether {@code part} can be baked.  The answer is cached on the part and
     * revalidated when its box count changes.
     */
    private static boolean isSupported(ModelRenderer part) {
        ModelPartExtension ext = (ModelPartExtension) part;
        int state = ext.efficiententities$getSupportState();
        List<ModelBox> cubes = part.cubeList;
        if (cubes == null) return false;
        int validated = cubes.size() + 1;
        if (state == validated) return true;
        if (state == UNSUPPORTED) return false;

        boolean supported = !OVERRIDES_PART_RENDER.get(part.getClass());
        for (int i = 0, n = cubes.size(); supported && i < n; i++) {
            supported = geometry(cubes.get(i)).supported;
        }
        ext.efficiententities$setSupportState(supported ? validated : UNSUPPORTED);
        return supported;
    }

    private static BoxGeometry geometry(ModelBox box) {
        ModelBoxExtension ext = (ModelBoxExtension) box;
        BoxGeometry geometry = ext.efficiententities$getGeometry();
        if (geometry == null) {
            geometry = OVERRIDES_BOX_RENDER.get(box.getClass())
                ? BoxGeometry.UNSUPPORTED
                : BoxGeometry.fromQuads(ext.efficiententities$getQuads(),
                                        EfficientModelRenderer::isNormalInverted);
            ext.efficiententities$setGeometry(geometry);
        }
        return geometry;
    }

    /** Vanilla never inverts normals; the accessor only matters for mods that do, so tolerate its absence. */
    private static boolean isNormalInverted(TexturedQuad quad) {
        return quad instanceof AccessorTexturedQuad && ((AccessorTexturedQuad) quad).efficiententities$isNormalInverted();
    }

    // ------------------------------------------------------------------ Geometry

    private void emitBox(BoxGeometry g, float scale) {
        int vertices = g.vertexCount();
        if (!ensureCapacity(vertices)) return;
        if (corners.length < g.positionCount * 3) corners = new float[g.positionCount * 3];
        writeBox(g, scale, matrix.array(), matrix.offset(), corners, buffer, vertexCount * BYTES_PER_VERTEX);
        vertexCount += vertices;
        boxes++;
    }

    /**
     * Writes the box's {@link BoxGeometry#vertexCount()} vertices to {@code dst}
     * at byte offset {@code at}, transformed by the row-major matrix at
     * {@code m[o]}.  Each distinct corner is transformed once; each quad's
     * normal is rotated by the matrix's upper-left 3×3, which is valid because
     * bone transforms contain only rotations and translations.
     *
     * @param corners scratch space for at least {@code positionCount * 3} floats
     */
    static void writeBox(BoxGeometry g, float scale, float[] m, int o, float[] corners, ByteBuffer dst, int at) {
        float m00 = m[o],     m01 = m[o + 1], m02 = m[o + 2],  m03 = m[o + 3];
        float m10 = m[o + 4], m11 = m[o + 5], m12 = m[o + 6],  m13 = m[o + 7];
        float m20 = m[o + 8], m21 = m[o + 9], m22 = m[o + 10], m23 = m[o + 11];

        float[] p = g.positions;
        float[] c = corners;
        for (int i = 0, n = g.positionCount * 3; i < n; i += 3) {
            float x = p[i] * scale, y = p[i + 1] * scale, z = p[i + 2] * scale;
            c[i]     = m00 * x + m01 * y + m02 * z + m03;
            c[i + 1] = m10 * x + m11 * y + m12 * z + m13;
            c[i + 2] = m20 * x + m21 * y + m22 * z + m23;
        }

        int[] indices = g.indices;
        float[] uvs = g.uvs, normals = g.normals;
        for (int q = 0, v = 0; q < g.quadCount; q++) {
            float nx = normals[q * 3], ny = normals[q * 3 + 1], nz = normals[q * 3 + 2];
            int normal = packNormal(m00 * nx + m01 * ny + m02 * nz,
                                    m10 * nx + m11 * ny + m12 * nz,
                                    m20 * nx + m21 * ny + m22 * nz);
            for (int k = 0; k < 4; k++, v++, at += BYTES_PER_VERTEX) {
                int ci = indices[v] * 3;
                dst.putFloat(at,      c[ci]);
                dst.putFloat(at + 4,  c[ci + 1]);
                dst.putFloat(at + 8,  c[ci + 2]);
                dst.putFloat(at + 12, uvs[v * 2]);
                dst.putFloat(at + 16, uvs[v * 2 + 1]);
                dst.putInt  (at + 20, normal);
            }
        }
    }

    /**
     * Packs a normal the way {@code BufferBuilder.normal} does for
     * {@code GL_BYTE} normals, as three bytes followed by a zero pad byte.
     */
    private static int packNormal(float nx, float ny, float nz) {
        int x = (int) (nx * 127f) & 0xFF;
        int y = (int) (ny * 127f) & 0xFF;
        int z = (int) (nz * 127f) & 0xFF;
        return LITTLE_ENDIAN ? x | y << 8 | z << 16 : x << 24 | y << 16 | z << 8;
    }

    // ------------------------------------------------------------------ Drawing

    private void draw(int first, int count) {
        if (failed) return;
        try {
            drawUnchecked(first, count);
        } catch (LinkageError | RuntimeException e) {
            disable("Drawing failed", e);
        }
    }

    private void drawUnchecked(int first, int count) {
        if (persistent) {
            int vbo = vbos[slice];
            PersistentBuffer.flush(vbo, (long) first * BYTES_PER_VERTEX, (long) count * BYTES_PER_VERTEX);
            if (useVao) {
                VaoHelper.bindVao(vaos[slice]);
                GL11.glDrawArrays(GL11.GL_QUADS, first, count);
                VaoHelper.bindVao(0);
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                enableClientArrays();
                GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, 0L);
                GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, 12L);
                GL11.glNormalPointer(GL11.GL_BYTE, BYTES_PER_VERTEX, 20L);
                GL11.glDrawArrays(GL11.GL_QUADS, first, count);
                disableClientArrays();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
        } else {
            ByteBuffer b = buffer;
            int base = first * BYTES_PER_VERTEX;
            enableClientArrays();
            b.position(base);
            GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, b);
            b.position(base + 12);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, b);
            b.position(base + 20);
            GL11.glNormalPointer(GL11.GL_BYTE, BYTES_PER_VERTEX, b);
            b.position(0);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
            disableClientArrays();
        }
        draws++;
    }

    private static void enableClientArrays() {
        // glTexCoordPointer targets the client-active unit; the lightmap path can leave it on unit 1.
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
    }

    private static void disableClientArrays() {
        GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    // ------------------------------------------------------------------ Buffer allocation

    /** Allocates {@link #BUFFER_SLICES} mapped slices into the given arrays; all or nothing. */
    private boolean allocateSlices(int bytes, int[] outVbos, int[] outVaos, ByteBuffer[] outMapped) {
        for (int i = 0; i < BUFFER_SLICES; i++) {
            int vbo = PersistentBuffer.createBuffer();
            PersistentBuffer.initStorage(vbo, bytes, STORAGE_FLAGS);
            ByteBuffer map = PersistentBuffer.map(vbo, bytes, MAP_FLAGS);
            if (map == null) {
                PersistentBuffer.deleteBuffer(vbo);
                for (int j = 0; j < i; j++) deleteSlice(outVbos[j], outVaos[j]);
                return false;
            }
            outVbos[i]   = vbo;
            outVaos[i]   = useVao ? createVao(vbo) : 0;
            outMapped[i] = map.order(ByteOrder.nativeOrder());
        }
        return true;
    }

    /** Records the vertex layout so each draw needs one bind instead of six state calls. */
    private static int createVao(int vbo) {
        int vao = VaoHelper.createVao();
        VaoHelper.bindVao(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        enableClientArrays();
        // Offsets are from byte 0; glDrawArrays' first argument selects the batch.
        GL11.glVertexPointer(3, GL11.GL_FLOAT, BYTES_PER_VERTEX, 0L);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BYTES_PER_VERTEX, 12L);
        GL11.glNormalPointer(GL11.GL_BYTE, BYTES_PER_VERTEX, 20L);
        VaoHelper.bindVao(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return vao;
    }

    private static void deleteSlice(int vbo, int vao) {
        PersistentBuffer.unmap(vbo);
        if (vao != 0) VaoHelper.deleteVao(vao);
        PersistentBuffer.deleteBuffer(vbo);
    }

    private boolean ensureCapacity(int vertices) {
        long needed = (long) (vertexCount + vertices) * BYTES_PER_VERTEX;
        return needed <= capacity || grow(needed);
    }

    /**
     * Replaces the buffers with larger ones, carrying the vertices not yet
     * drawn over to the start of the new buffer.  Outgrown slices are deleted
     * once their fences show the GPU no longer reads them.
     */
    private boolean grow(long neededBytes) {
        long target = Math.max(capacity + (long) (capacity >> 1), neededBytes);
        if (target > Integer.MAX_VALUE) return giveUp("vertex buffer would exceed 2 GB");
        int newCapacity = (int) target;

        ByteBuffer old = buffer;
        int pendingVertices = vertexCount - batchStart;
        int pendingStart = batchStart * BYTES_PER_VERTEX;

        if (persistent) {
            int[] newVbos = new int[BUFFER_SLICES], newVaos = new int[BUFFER_SLICES];
            ByteBuffer[] newMapped = new ByteBuffer[BUFFER_SLICES];
            try {
                if (!allocateSlices(newCapacity, newVbos, newVaos, newMapped)) return giveUp("could not map a larger VBO");
            } catch (LinkageError | RuntimeException e) {
                disable("Could not grow the vertex buffer", e);
                return false;
            }
            for (int i = 0; i < BUFFER_SLICES; i++) {
                final int vbo = vbos[i], vao = vaos[i];
                pendingDeletes[i].push(() -> deleteSlice(vbo, vao));
            }
            System.arraycopy(newVbos, 0, vbos, 0, BUFFER_SLICES);
            System.arraycopy(newVaos, 0, vaos, 0, BUFFER_SLICES);
            System.arraycopy(newMapped, 0, mapped, 0, BUFFER_SLICES);
            buffer = mapped[slice];
        } else {
            buffer = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        }

        if (pendingVertices > 0) {
            ByteBuffer src = old.duplicate();
            src.limit(pendingStart + pendingVertices * BYTES_PER_VERTEX);
            src.position(pendingStart);
            ByteBuffer dst = buffer.duplicate();
            dst.position(0);
            dst.put(src);
        }
        vertexCount = pendingVertices;
        batchStart  = 0;

        EfficientEntities.LOG.info("[EfficientEntities] Grew vertex buffer: {} KB → {} KB.", capacity / 1024, newCapacity / 1024);
        capacity = newCapacity;
        return true;
    }

    private boolean giveUp(String reason) {
        disable(reason, null);
        return false;
    }

    /** Hands entity rendering back to vanilla for the rest of the session. */
    private void disable(String reason, Throwable cause) {
        if (!failed) {
            EfficientEntities.LOG.error("[EfficientEntities] {}; entity models will use vanilla rendering.", reason, cause);
        }
        failed  = true;
        active  = false;
        inBatch = false;
        vanillaDepth = 0;
        while (vanillaRootCount > 0) vanillaRoots[--vanillaRootCount] = null;
    }

    // ------------------------------------------------------------------ Diagnostics

    private void logStatistics() {
        int period = EEConfig.statisticsLogPeriod;
        if (period > 0 && ++frames < period) return;
        if (period > 0) {
            EfficientEntities.LOG.info(
                "[EfficientEntities] Last {} frames: {} draws, {} boxes, {} single-part batches, {} vanilla parts.",
                period, draws, boxes, autoBatches, vanillaParts);
        }
        frames = draws = boxes = autoBatches = vanillaParts = 0;
    }

    // ------------------------------------------------------------------ Class checks

    private static boolean isOptiFinePresent() {
        ClassLoader loader = EfficientModelRenderer.class.getClassLoader();
        return loader.getResource("optifine/OptiFineForgeTweaker.class") != null
            || loader.getResource("net/optifine/Config.class") != null;
    }

    /**
     * {@code true} for subclasses of {@code base} that declare one of the named
     * methods, i.e. override what the renderer would otherwise replace.  Both
     * MCP (dev) and SRG (production) names are checked.  A class whose methods
     * cannot be resolved counts as overriding, which keeps it on vanilla.
     */
    private static ClassValue<Boolean> overrides(Class<?> base, Class<?>[] parameters, String... names) {
        return new ClassValue<Boolean>() {
            @Override
            protected Boolean computeValue(Class<?> type) {
                try {
                    for (Class<?> c = type; c != null && c != base; c = c.getSuperclass()) {
                        for (String name : names) {
                            try {
                                c.getDeclaredMethod(name, parameters);
                                return true;
                            } catch (NoSuchMethodException ignored) {
                                // keep looking
                            }
                        }
                    }
                    return false;
                } catch (LinkageError e) {
                    return true;
                }
            }
        };
    }
}
