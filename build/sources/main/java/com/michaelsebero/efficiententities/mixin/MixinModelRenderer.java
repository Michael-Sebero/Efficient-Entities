package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import com.michaelsebero.efficiententities.renderer.BatchedModelRenderer;

import net.minecraft.client.model.ModelRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Mixin to replace ModelRenderer.render() with our batched implementation.
 * This is the injection point that redirects all entity rendering through our system.
 */
@Mixin(ModelRenderer.class)
public class MixinModelRenderer {
    
    /**
     * Replace the default render method with our batched renderer.
     * This eliminates per-bone glPushMatrix/glPopMatrix calls and batches geometry.
     * 
     * @reason Performance optimization - batched rendering with CPU transforms
     * @author Michael Sebero
     */
    @Overwrite
    @SideOnly(Side.CLIENT)
    public void render(float scale) {
        BatchedModelRenderer.getInstance().renderModel(
            (ModelRenderer)(Object)this, 
            scale
        );
    }
}
