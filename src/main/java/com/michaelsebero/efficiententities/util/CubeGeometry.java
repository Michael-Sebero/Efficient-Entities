package com.michaelsebero.efficiententities.util;

import net.minecraft.client.model.ModelRenderer;

/**
 * Stores pre-computed geometry (corner positions and normalised UV coordinates)
 * for a single {@link net.minecraft.client.model.ModelBox}.
 *
 * UV coordinates are pre-divided by the texture dimensions so they can be
 * uploaded to the GPU without further arithmetic each frame.
 *
 * Face layout (matches vanilla ModelBox rendering order):
 *   +X, -X, +Y, -Y, +Z, -Z
 */
public class CubeGeometry {

    // World-space corners.
    public final float x0, y0, z0;
    public final float x1, y1, z1;

    // Normalised UV extents per face.  Prefix u/v = horizontal/vertical.
    public final float upx0, upx1, vpx0, vpx1; // +X face
    public final float unx0, unx1, vnx0, vnx1; // -X face
    public final float upy0, upy1, vpy0, vpy1; // +Y face
    public final float uny0, uny1, vny0, vny1; // -Y face
    public final float upz0, upz1, vpz0, vpz1; // +Z face
    public final float unz0, unz1, vnz0, vnz1; // -Z face

    public CubeGeometry(ModelRenderer renderer,
                        int texU, int texV,
                        float originX, float originY, float originZ,
                        int sizeX, int sizeY, int sizeZ,
                        float expansion, boolean mirror) {

        float tw = renderer.textureWidth;
        float th = renderer.textureHeight;

        x0 = mirror ? originX + sizeX + expansion : originX - expansion;
        x1 = mirror ? originX - expansion         : originX + sizeX + expansion;
        y0 = originY - expansion;
        y1 = originY + sizeY + expansion;
        z0 = originZ - expansion;
        z1 = originZ + sizeZ + expansion;

        // +X face
        upx0 = (texU + sizeZ + sizeX)         / tw;
        upx1 = (texU + sizeZ + sizeX + sizeZ) / tw;
        vpx0 = (texV + sizeZ)                 / th;
        vpx1 = (texV + sizeZ + sizeY)         / th;

        // -X face
        unx0 = texU                            / tw;
        unx1 = (texU + sizeZ)                 / tw;
        vnx0 = (texV + sizeZ)                 / th;
        vnx1 = (texV + sizeZ + sizeY)         / th;

        // +Y face (top)
        upy0 = (texU + sizeZ + sizeX)         / tw;
        upy1 = (texU + sizeZ + sizeX + sizeX) / tw;
        vpy0 = (texV + sizeZ)                 / th;
        vpy1 = texV                            / th;

        // -Y face (bottom)
        uny0 = (texU + sizeZ)                 / tw;
        uny1 = (texU + sizeZ + sizeX)         / tw;
        vny0 = texV                            / th;
        vny1 = (texV + sizeZ)                 / th;

        // +Z face
        upz0 = (texU + sizeZ + sizeX + sizeZ)         / tw;
        upz1 = (texU + sizeZ + sizeX + sizeZ + sizeX) / tw;
        vpz0 = (texV + sizeZ)                          / th;
        vpz1 = (texV + sizeZ + sizeY)                  / th;

        // -Z face
        unz0 = (texU + sizeZ)                 / tw;
        unz1 = (texU + sizeZ + sizeX)         / tw;
        vnz0 = (texV + sizeZ)                 / th;
        vnz1 = (texV + sizeZ + sizeY)         / th;
    }
}
