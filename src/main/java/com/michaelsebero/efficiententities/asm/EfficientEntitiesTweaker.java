package com.michaelsebero.efficiententities.asm;

import java.io.File;
import java.util.List;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Registers {@link EfficientEntitiesTransformer} with the
 * {@link LaunchClassLoader}.  Not meant to be a primary tweaker.
 */
public class EfficientEntitiesTweaker implements ITweaker {

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) { }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        classLoader.registerTransformer(EfficientEntitiesTransformer.class.getName());
    }

    @Override
    public String getLaunchTarget() {
        throw new RuntimeException("EfficientEntitiesTweaker cannot be used as the primary tweaker.");
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
