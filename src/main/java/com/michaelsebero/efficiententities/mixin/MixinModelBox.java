package com.michaelsebero.efficiententities.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.michaelsebero.efficiententities.renderer.BoxGeometry;
import com.michaelsebero.efficiententities.renderer.ModelBoxExtension;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;

/**
 * Gives the renderer the box's quads and a slot to cache the {@link BoxGeometry}
 * built from them.  The geometry is derived from the quads on first render,
 * the same moment vanilla compiles its display list, so it reproduces exactly
 * what vanilla would draw (mirroring, inflation, UVs, normals and any quads a
 * mod replaced after construction).
 */
@Mixin(ModelBox.class)
public abstract class MixinModelBox implements ModelBoxExtension {

    @Shadow @Final private TexturedQuad[] quadList;

    @Unique
    private BoxGeometry efficiententities$geometry;

    @Override
    public TexturedQuad[] efficiententities$getQuads() {
        return quadList;
    }

    @Override
    public BoxGeometry efficiententities$getGeometry() {
        return efficiententities$geometry;
    }

    @Override
    public void efficiententities$setGeometry(BoxGeometry geometry) {
        efficiententities$geometry = geometry;
    }
}
