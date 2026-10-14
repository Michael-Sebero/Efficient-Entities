package com.michaelsebero.efficiententities.renderer;

import java.util.function.Predicate;

import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.util.math.Vec3d;

/**
 * Pre-baked vertex data for one {@link net.minecraft.client.model.ModelBox},
 * read from the box's own {@link TexturedQuad}s.
 *
 * <p>Taking the quads as the source of truth, rather than re-deriving corners
 * and UVs from the constructor arguments, makes the output identical to
 * vanilla's {@code TexturedQuad.draw} by construction: mirrored boxes keep
 * their flipped winding and outward normals, inflated boxes keep their
 * offsets, and quads a mod rewrote after construction are honoured.
 *
 * <h3>Layout</h3>
 * {@link #positions} holds each distinct corner once (8 for an ordinary box)
 * so each is transformed once per frame; {@link #indices} maps every emitted
 * vertex to its corner.  Quads of zero area, e.g. the edge faces of a flat
 * plane, cannot produce fragments and are dropped.
 */
public final class BoxGeometry {

    /** Marker for boxes that must be drawn by vanilla. */
    public static final BoxGeometry UNSUPPORTED =
        new BoxGeometry(false, new float[0], 0, new int[0], new float[0], new float[0], 0);

    final boolean supported;
    /** Distinct corner positions in model units (before {@code scale}), xyz-interleaved. */
    final float[] positions;
    final int     positionCount;
    /** Corner index of each emitted vertex; four per quad. */
    final int[]   indices;
    /** Texture coordinates of each emitted vertex, uv-interleaved. */
    final float[] uvs;
    /** Model-space normal of each quad, xyz-interleaved, exactly as vanilla computes it. */
    final float[] normals;
    final int     quadCount;

    private BoxGeometry(boolean supported, float[] positions, int positionCount,
                        int[] indices, float[] uvs, float[] normals, int quadCount) {
        this.supported     = supported;
        this.positions     = positions;
        this.positionCount = positionCount;
        this.indices       = indices;
        this.uvs           = uvs;
        this.normals       = normals;
        this.quadCount     = quadCount;
    }

    public int vertexCount() {
        return quadCount * 4;
    }

    /**
     * @param quads          the box's quads, in draw order
     * @param normalInverted reads {@code TexturedQuad.invertNormal}
     */
    public static BoxGeometry fromQuads(TexturedQuad[] quads, Predicate<TexturedQuad> normalInverted) {
        if (quads == null) return UNSUPPORTED;

        int maxVertices = quads.length * 4;
        float[] positions = new float[maxVertices * 3];
        int[]   indices   = new int[maxVertices];
        float[] uvs       = new float[maxVertices * 2];
        float[] normals   = new float[quads.length * 3];
        int positionCount = 0, quadCount = 0;

        for (TexturedQuad quad : quads) {
            if (quad == null) return UNSUPPORTED;
            PositionTextureVertex[] v = quad.vertexPositions;
            // TexturedQuad.draw always emits exactly the first four vertices.
            if (v == null || v.length < 4) return UNSUPPORTED;
            if (hasZeroArea(v)) continue;

            // Same expression as TexturedQuad.draw, so normals match bit for bit.
            Vec3d edge0  = v[1].vector3D.subtractReverse(v[0].vector3D);
            Vec3d edge1  = v[1].vector3D.subtractReverse(v[2].vector3D);
            Vec3d normal = edge1.crossProduct(edge0).normalize();
            float sign = normalInverted.test(quad) ? -1f : 1f;
            normals[quadCount * 3]     = sign * (float) normal.x;
            normals[quadCount * 3 + 1] = sign * (float) normal.y;
            normals[quadCount * 3 + 2] = sign * (float) normal.z;

            for (int k = 0; k < 4; k++) {
                PositionTextureVertex vertex = v[k];
                float x = (float) vertex.vector3D.x;
                float y = (float) vertex.vector3D.y;
                float z = (float) vertex.vector3D.z;

                int index = indexOf(positions, positionCount, x, y, z);
                if (index < 0) {
                    index = positionCount++;
                    positions[index * 3]     = x;
                    positions[index * 3 + 1] = y;
                    positions[index * 3 + 2] = z;
                }

                int out = quadCount * 4 + k;
                indices[out]     = index;
                uvs[out * 2]     = vertex.texturePositionX;
                uvs[out * 2 + 1] = vertex.texturePositionY;
            }
            quadCount++;
        }

        return new BoxGeometry(true, positions, positionCount, indices, uvs, normals, quadCount);
    }

    private static int indexOf(float[] positions, int count, float x, float y, float z) {
        for (int i = 0; i < count; i++) {
            if (positions[i * 3] == x && positions[i * 3 + 1] == y && positions[i * 3 + 2] == z) return i;
        }
        return -1;
    }

    /** True when both triangles of the quad are degenerate, so it can never cover a pixel. */
    private static boolean hasZeroArea(PositionTextureVertex[] v) {
        return isZero(cross(v[0].vector3D, v[1].vector3D, v[2].vector3D))
            && isZero(cross(v[0].vector3D, v[2].vector3D, v[3].vector3D));
    }

    private static Vec3d cross(Vec3d origin, Vec3d a, Vec3d b) {
        return a.subtract(origin).crossProduct(b.subtract(origin));
    }

    private static boolean isZero(Vec3d v) {
        return v.x == 0.0 && v.y == 0.0 && v.z == 0.0;
    }
}
