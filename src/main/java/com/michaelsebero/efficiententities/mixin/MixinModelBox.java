package com.michaelsebero.efficiententities.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import com.michaelsebero.efficiententities.util.CubeGeometry;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

/**
 * Attaches a {@link CubeGeometry} to every {@link ModelBox} at construction
 * time so the renderer can access pre-computed UV and corner data without
 * repeating the arithmetic every frame.
 *
 * <p>Also registers the cow's udder {@link ModelRenderer} as a vanilla-fallback
 * part. The udder is identified by its texture offset (52, 0) and its exact
 * box dimensions (4×3×2) which together are unique across all vanilla models.
 */
@Mixin(ModelBox.class)
public class MixinModelBox implements Supplier<CubeGeometry> {

    // Vanilla ModelCow udder: new ModelBox(this, 52, 0, -2, -3, -1, 4, 3, 2, 0)
    private static final int UDDER_TEX_U = 52;
    private static final int UDDER_TEX_V = 0;
    private static final int UDDER_DX    = 4;
    private static final int UDDER_DY    = 3;
    private static final int UDDER_DZ    = 2;

    @Unique
    private CubeGeometry geometry;

    @Inject(
        method = "<init>(Lnet/minecraft/client/model/ModelRenderer;IIFFFIIIFZ)V",
        at     = @At("RETURN")
    )
    private void captureGeometry(ModelRenderer renderer,
                                 int texU, int texV,
                                 float x, float y, float z,
                                 int dx, int dy, int dz,
                                 float delta, boolean mirror,
                                 CallbackInfo ci) {

        // Adjust origin to match vanilla's TexturedQuad corner calculation.
        float adjustedX = mirror ? x - dx - delta : x + delta;
        float adjustedY = y + delta;
        float adjustedZ = z + delta;

        this.geometry = new CubeGeometry(
            renderer,
            texU, texV,
            adjustedX, adjustedY, adjustedZ,
            dx, dy, dz,
            delta, mirror
        );

        // Register the cow udder renderer as a vanilla-fallback part.
        // Identified by texture offset + exact box dimensions — this combination
        // is unique across all vanilla models so no instanceof check is needed.
        if (texU == UDDER_TEX_U && texV == UDDER_TEX_V
                && dx == UDDER_DX && dy == UDDER_DY && dz == UDDER_DZ) {
            EfficientModelRenderer.registerVanillaRenderer(renderer);
        }
    }

    @Override
    public CubeGeometry get() {
        return geometry;
    }
}
