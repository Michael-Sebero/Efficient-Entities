package com.michaelsebero.efficiententities.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;

import com.michaelsebero.efficiententities.util.MatrixStack;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.Vec3d;

/**
 * Compares the renderer's baked vertices with the bytes vanilla's
 * {@code TexturedQuad.draw} writes into its display list, for every kind of
 * box vanilla can build: mirrored, inflated, flat planes and odd texture sizes.
 */
class VanillaParityTest {

    private static final int STRIDE = 24;

    private static final Field QUAD_LIST     = field(ModelBox.class, "quadList");
    private static final Field INVERT_NORMAL = field(TexturedQuad.class, "invertNormal");

    /** One box per vanilla-reachable configuration, drawn at the standard model scale. */
    @Test
    void bakedBoxesMatchVanillaDisplayListBytes() {
        Random random = new Random(1234);
        int compared = 0, skippedQuads = 0;

        for (int[] texture : new int[][] { { 64, 32 }, { 64, 64 }, { 128, 64 }, { 256, 128 } }) {
            for (float delta : new float[] { 0f, 0.1f, 0.25f, 0.5f, 1f, 1.75f }) {
                for (boolean mirror : new boolean[] { false, true }) {
                    for (int trial = 0; trial < 25; trial++) {
                        int dx = random.nextInt(9), dy = random.nextInt(9), dz = random.nextInt(9);
                        if (trial == 0) dz = 0; // flat plane, as used for wings and ears
                        ModelBox box = box(texture, random.nextInt(32), random.nextInt(16), mirror,
                            random.nextFloat() * 16 - 8, random.nextFloat() * 16 - 8, random.nextFloat() * 16 - 8,
                            dx, dy, dz, delta);

                        List<byte[]> vanilla = vanillaQuads(box, 0.0625F);
                        BoxGeometry geometry = geometry(box);
                        ByteBuffer baked = bake(geometry, identity(), 0.0625F);

                        int q = 0;
                        for (byte[] quad : vanilla) {
                            if (hasZeroArea(quad)) {
                                skippedQuads++;
                                continue;
                            }
                            assertQuadEquals(quad, baked, q++, null);
                            compared++;
                        }
                        assertEquals(q, geometry.quadCount, "every non-degenerate quad is emitted, in vanilla order");
                    }
                }
            }
        }
        assertTrue(compared > 3000, "compared " + compared + " quads");
        assertTrue(skippedQuads > 0, "planes were exercised");
    }

    /**
     * Bakes boxes through random bone transforms built the way vanilla's
     * {@code ModelRenderer.render} issues them to GL, and compares against
     * vanilla's bytes pushed through the same transform in double precision.
     */
    @Test
    void transformedBoxesMatchVanillaUnderGlTransform() {
        Random random = new Random(99);
        for (int trial = 0; trial < 500; trial++) {
            ModelBox box = box(new int[] { 64, 64 }, 0, 0, random.nextBoolean(),
                -4, -6, -2, 1 + random.nextInt(8), 1 + random.nextInt(8), 1 + random.nextInt(8),
                random.nextBoolean() ? 0f : 0.5f);

            MatrixStack stack = new MatrixStack();
            Matrix4f gl = new Matrix4f();
            for (int depth = 0; depth < 1 + random.nextInt(4); depth++) {
                float ox = rnd(random, 0.2f), oy = rnd(random, 0.2f), oz = rnd(random, 0.2f);
                float px = rnd(random, 8f), py = rnd(random, 24f), pz = rnd(random, 8f);
                float ax = rnd(random, 3.2f), ay = rnd(random, 3.2f), az = rnd(random, 3.2f);
                applyLikeRenderer(stack, ox, oy, oz, px, py, pz, ax, ay, az, 0.0625F);
                applyLikeVanillaGl(gl, ox, oy, oz, px, py, pz, ax, ay, az, 0.0625F);
            }

            List<byte[]> vanilla = vanillaQuads(box, 0.0625F);
            ByteBuffer baked = bake(geometry(box), stack, 0.0625F);
            int q = 0;
            for (byte[] quad : vanilla) {
                if (!hasZeroArea(quad)) assertQuadEquals(quad, baked, q++, gl);
            }
        }
    }

    @Test
    void matrixStackMatchesGlMatrixSemantics() {
        Random random = new Random(7);
        for (int trial = 0; trial < 1000; trial++) {
            MatrixStack stack = new MatrixStack();
            Matrix4f gl = new Matrix4f();
            for (int depth = 0; depth < 1 + random.nextInt(40); depth++) { // deeper than the old fixed 32-level stack
                stack.push();
                float ox = rnd(random, 1f), oy = rnd(random, 1f), oz = rnd(random, 1f);
                float px = rnd(random, 8f), py = rnd(random, 8f), pz = rnd(random, 8f);
                float ax = rnd(random, 3.2f), ay = rnd(random, 3.2f), az = rnd(random, 3.2f);
                applyLikeRenderer(stack, ox, oy, oz, px, py, pz, ax, ay, az, 0.0625F);
                applyLikeVanillaGl(gl, ox, oy, oz, px, py, pz, ax, ay, az, 0.0625F);
            }

            java.nio.FloatBuffer ours = java.nio.FloatBuffer.allocate(16);
            java.nio.FloatBuffer ref  = java.nio.FloatBuffer.allocate(16);
            stack.store(ours);
            gl.store(ref);
            ref.flip();
            for (int i = 0; i < 16; i++) {
                assertEquals(ref.get(i), ours.get(i), 2e-4f, "column-major element " + i);
            }
        }
    }

    // ------------------------------------------------------------------ comparisons

    private static void assertQuadEquals(byte[] vanilla, ByteBuffer baked, int quadIndex, Matrix4f transform) {
        ByteBuffer v = ByteBuffer.wrap(vanilla).order(ByteOrder.nativeOrder());
        float[] normal = vanillaNormal(v);

        for (int k = 0; k < 4; k++) {
            int vb = k * STRIDE, bb = (quadIndex * 4 + k) * STRIDE;
            Vector4f pos = new Vector4f(v.getFloat(vb), v.getFloat(vb + 4), v.getFloat(vb + 8), 1f);
            if (transform != null) Matrix4f.transform(transform, pos, pos);

            String where = "quad " + quadIndex + " vertex " + k;
            assertEquals(pos.x, baked.getFloat(bb),     1e-5f, where + " x");
            assertEquals(pos.y, baked.getFloat(bb + 4), 1e-5f, where + " y");
            assertEquals(pos.z, baked.getFloat(bb + 8), 1e-5f, where + " z");
            assertEquals(v.getFloat(vb + 12), baked.getFloat(bb + 12), 0f, where + " u");
            assertEquals(v.getFloat(vb + 16), baked.getFloat(bb + 16), 0f, where + " v");

            if (transform == null) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(vanilla[vb + 20 + c], baked.get(bb + 20 + c), where + " normal byte " + c);
                }
            } else {
                Vector4f n = new Vector4f(normal[0], normal[1], normal[2], 0f);
                Matrix4f.transform(transform, n, n);
                float[] expected = { n.x, n.y, n.z };
                for (int c = 0; c < 3; c++) {
                    int want = (int) (expected[c] * 127f);
                    assertEquals(want, baked.get(bb + 20 + c), 1, where + " normal byte " + c);
                }
            }
            assertEquals(0, baked.get(bb + 23), where + " padding");
        }
    }

    /** Recomputes vanilla's float normal from the quad's own positions (as TexturedQuad.draw does). */
    private static float[] vanillaNormal(ByteBuffer v) {
        Vec3d p0 = new Vec3d(v.getFloat(0), v.getFloat(4), v.getFloat(8));
        Vec3d p1 = new Vec3d(v.getFloat(24), v.getFloat(28), v.getFloat(32));
        Vec3d p2 = new Vec3d(v.getFloat(48), v.getFloat(52), v.getFloat(56));
        Vec3d n = p1.subtractReverse(p2).crossProduct(p1.subtractReverse(p0)).normalize();
        return new float[] { (float) n.x, (float) n.y, (float) n.z };
    }

    private static boolean hasZeroArea(byte[] quad) {
        ByteBuffer v = ByteBuffer.wrap(quad).order(ByteOrder.nativeOrder());
        Vector3f[] p = new Vector3f[4];
        for (int k = 0; k < 4; k++) p[k] = new Vector3f(v.getFloat(k * STRIDE), v.getFloat(k * STRIDE + 4), v.getFloat(k * STRIDE + 8));
        Vector3f a = Vector3f.cross(Vector3f.sub(p[1], p[0], null), Vector3f.sub(p[2], p[0], null), null);
        Vector3f b = Vector3f.cross(Vector3f.sub(p[2], p[0], null), Vector3f.sub(p[3], p[0], null), null);
        return a.lengthSquared() == 0f && b.lengthSquared() == 0f;
    }

    // ------------------------------------------------------------------ vanilla and baked output

    /** Captures what TexturedQuad.draw writes for each quad of {@code box}. */
    private static List<byte[]> vanillaQuads(ModelBox box, float scale) {
        List<byte[]> quads = new ArrayList<>();
        BufferBuilder builder = new BufferBuilder(256);
        for (TexturedQuad quad : quads(box)) {
            try {
                quad.draw(builder, scale);
            } catch (IllegalStateException expected) {
                // draw() ends by calling Tessellator.getInstance().draw() on the shared
                // Tessellator, which needs GL; by then our builder holds the quad.
            }
            builder.finishDrawing();
            assertEquals(DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL, builder.getVertexFormat());
            assertEquals(4, builder.getVertexCount());
            ByteBuffer data = builder.getByteBuffer();
            byte[] bytes = new byte[4 * STRIDE];
            data.position(0);
            data.get(bytes);
            quads.add(bytes);
            builder.reset();
        }
        return quads;
    }

    private static ByteBuffer bake(BoxGeometry geometry, MatrixStack matrix, float scale) {
        ByteBuffer out = ByteBuffer.allocate(geometry.vertexCount() * STRIDE).order(ByteOrder.nativeOrder());
        EfficientModelRenderer.writeBox(geometry, scale, matrix.array(), matrix.offset(),
            new float[geometry.positionCount * 3], out, 0);
        return out;
    }

    private static BoxGeometry geometry(ModelBox box) {
        return BoxGeometry.fromQuads(quads(box), quad -> {
            try {
                return INVERT_NORMAL.getBoolean(quad);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
    }

    // ------------------------------------------------------------------ transforms

    /** What EfficientModelRenderer.renderTree does for one bone. */
    private static void applyLikeRenderer(MatrixStack m, float ox, float oy, float oz, float px, float py, float pz,
                                          float ax, float ay, float az, float scale) {
        m.translate(ox + px * scale, oy + py * scale, oz + pz * scale);
        m.rotateZ(az);
        m.rotateY(ay);
        m.rotateX(ax);
    }

    /** What vanilla's ModelRenderer.render issues to GL for one bone. */
    private static void applyLikeVanillaGl(Matrix4f m, float ox, float oy, float oz, float px, float py, float pz,
                                           float ax, float ay, float az, float scale) {
        m.translate(new Vector3f(ox, oy, oz));
        m.translate(new Vector3f(px * scale, py * scale, pz * scale));
        m.rotate((float) Math.toRadians(az * (180F / (float) Math.PI)), new Vector3f(0, 0, 1));
        m.rotate((float) Math.toRadians(ay * (180F / (float) Math.PI)), new Vector3f(0, 1, 0));
        m.rotate((float) Math.toRadians(ax * (180F / (float) Math.PI)), new Vector3f(1, 0, 0));
    }

    private static MatrixStack identity() {
        return new MatrixStack();
    }

    // ------------------------------------------------------------------ fixtures

    private static ModelBox box(int[] texture, int u, int v, boolean mirror,
                                float x, float y, float z, int dx, int dy, int dz, float delta) {
        ModelBase model = new ModelBase() { };
        model.textureWidth = texture[0];
        model.textureHeight = texture[1];
        ModelRenderer part = new ModelRenderer(model, u, v);
        part.mirror = mirror;
        part.addBox(x, y, z, dx, dy, dz, delta);
        return part.cubeList.get(0);
    }

    private static TexturedQuad[] quads(ModelBox box) {
        try {
            return (TexturedQuad[]) QUAD_LIST.get(box);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private static float rnd(Random random, float range) {
        return random.nextInt(4) == 0 ? 0f : (random.nextFloat() * 2 - 1) * range;
    }
}
