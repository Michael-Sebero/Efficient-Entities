package com.michaelsebero.efficiententities.renderer;

/**
 * Implemented on {@link net.minecraft.client.model.ModelRenderer} by
 * {@code MixinModelRenderer}; caches whether the part can be batched.
 */
public interface ModelPartExtension {

    int efficiententities$getSupportState();

    void efficiententities$setSupportState(int state);
}
