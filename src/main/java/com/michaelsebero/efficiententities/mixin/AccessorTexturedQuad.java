package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.model.TexturedQuad;

@Mixin(TexturedQuad.class)
public interface AccessorTexturedQuad {

    @Accessor("invertNormal")
    boolean efficiententities$isNormalInverted();
}
