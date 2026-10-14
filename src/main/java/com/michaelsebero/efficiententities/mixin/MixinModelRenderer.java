package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import com.michaelsebero.efficiententities.renderer.ModelPartExtension;
import net.minecraft.client.model.ModelRenderer;

/**
 * Routes {@link ModelRenderer#render(float)} into {@link EfficientModelRenderer}.
 *
 * <p>When the renderer declines a part (OptiFine, a chest tile-entity renderer,
 * a subclass that overrides {@code render}, or a vanilla subtree it dispatched
 * itself) the vanilla body runs untouched; the {@code RETURN} hook lets the
 * renderer close the vanilla scope it opened for that part.
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer implements ModelPartExtension {

    @Unique
    private int efficiententities$supportState;

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true)
    private void efficiententities$render(float scale, CallbackInfo ci) {
        if (EfficientModelRenderer.renderPart((ModelRenderer) (Object) this, scale)) {
            ci.cancel();
        }
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void efficiententities$afterVanillaRender(float scale, CallbackInfo ci) {
        EfficientModelRenderer.afterVanillaRender((ModelRenderer) (Object) this);
    }

    @Override
    public int efficiententities$getSupportState() {
        return efficiententities$supportState;
    }

    @Override
    public void efficiententities$setSupportState(int state) {
        efficiententities$supportState = state;
    }
}
