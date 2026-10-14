package com.michaelsebero.efficiententities.renderer;

import net.minecraft.client.model.TexturedQuad;

/** Implemented on {@link net.minecraft.client.model.ModelBox} by {@code MixinModelBox}. */
public interface ModelBoxExtension {

    TexturedQuad[] efficiententities$getQuads();

    BoxGeometry efficiententities$getGeometry();

    void efficiententities$setGeometry(BoxGeometry geometry);
}
