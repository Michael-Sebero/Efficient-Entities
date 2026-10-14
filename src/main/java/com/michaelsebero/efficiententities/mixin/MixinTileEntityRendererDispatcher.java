package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;

/**
 * Leaves chest tile-entity renderers (vanilla, ender and modded {@code *ChestRenderer}s)
 * on the vanilla path.  Every chest draw, in the world or as an item, is dispatched
 * through this method, so one scope here replaces the old per-part stack-trace scan.
 */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcher {

    @Shadow
    public abstract <T extends TileEntity> TileEntitySpecialRenderer<T> getRenderer(Class<? extends TileEntity> teClass);

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("HEAD"))
    private void efficiententities$enterChest(TileEntity te, double x, double y, double z,
                                              float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (efficiententities$isChest(te)) EfficientModelRenderer.enterVanillaScope();
    }

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V", at = @At("RETURN"))
    private void efficiententities$exitChest(TileEntity te, double x, double y, double z,
                                             float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (efficiententities$isChest(te)) EfficientModelRenderer.exitVanillaScope();
    }

    /** Looks up by class rather than instance so HEAD and RETURN always agree. */
    @Unique
    private boolean efficiententities$isChest(TileEntity te) {
        if (te == null) return false;
        TileEntitySpecialRenderer<?> renderer = getRenderer(te.getClass());
        return renderer != null && EfficientModelRenderer.isChestRenderer(renderer.getClass());
    }
}
