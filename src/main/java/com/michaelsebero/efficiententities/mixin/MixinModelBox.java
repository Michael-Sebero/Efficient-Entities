package com.michaelsebero.efficiententities.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.michaelsebero.efficiententities.util.CubeGeometry;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;

/**
 * Attaches a {@link CubeGeometry} to every {@link ModelBox} at construction
 * time so the renderer can access pre-computed UV and corner data without
 * repeating the arithmetic every frame.
 */
@Mixin(ModelBox.class)
public class MixinModelBox implements Supplier<CubeGeometry> {

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
    }

    @Override
    public CubeGeometry get() {
        return geometry;
    }
}
