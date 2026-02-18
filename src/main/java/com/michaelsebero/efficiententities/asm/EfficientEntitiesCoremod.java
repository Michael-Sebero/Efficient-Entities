package com.michaelsebero.efficiententities.asm;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.launcher.FMLInjectionAndSortingTweaker;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * FML core-mod plugin for Efficient Entities.
 *
 * Registers {@link EfficientEntitiesTweaker} so the ASM transformer is loaded
 * after FML's own class-loading infrastructure is ready, and bootstraps Mixin
 * when running in a non-obfuscated development environment.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions("com.michaelsebero.efficiententities.asm")
public class EfficientEntitiesCoremod implements IFMLLoadingPlugin {

    // Run after FML's own tweakers (order 1000).
    private static final int TWEAKER_ORDER = 1001;

    @SuppressWarnings("unchecked")
    public EfficientEntitiesCoremod() {
        try {
            List<ITweaker> activeTweakers  = (List<ITweaker>) Launch.blackboard.get("Tweaks");
            List<String>   pendingClasses  = (List<String>)  Launch.blackboard.get("TweakClasses");

            boolean fmlAlreadyInjected = activeTweakers.stream()
                .anyMatch(FMLInjectionAndSortingTweaker.class::isInstance);

            if (fmlAlreadyInjected) {
                activeTweakers.add(new EfficientEntitiesTweaker());
            } else {
                pendingClasses.add(EfficientEntitiesTweaker.class.getName());
            }

            Field tweakSortingField = CoreModManager.class.getDeclaredField("tweakSorting");
            tweakSortingField.setAccessible(true);
            ((Map<String, Integer>) tweakSortingField.get(null))
                .put(EfficientEntitiesTweaker.class.getName(), TWEAKER_ORDER);

        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Failed to register EfficientEntitiesTweaker", ex);
        }
    }

    @Override public String[] getASMTransformerClass()              { return null; }
    @Override public String   getModContainerClass()                { return null; }
    @Override public String   getSetupClass()                       { return null; }
    @Override public String   getAccessTransformerClass()           { return null; }

    @Override
    public void injectData(Map<String, Object> data) {
        // In a dev environment FML has not yet initialised Mixin.
        if (Boolean.FALSE.equals(data.get("runtimeDeobfuscationEnabled"))) {
            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setObfuscationContext("searge");
        }
    }
}
