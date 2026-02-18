package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Replaces {@link ModelRenderer#render(float)} to forward to
 * {@link EfficientModelRenderer} for GPU-batched rendering.
 */
@Mixin(ModelRenderer.class)
public abstract class MixinModelRenderer {

    /**
     * @reason Redirect to EfficientModelRenderer for VBO-batched rendering.
     * @author michaelsebero
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    public void render(float scale) {
        EfficientModelRenderer.instance().render((ModelRenderer) (Object) this, scale);
    }
}
