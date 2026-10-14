package com.michaelsebero.efficiententities.asm;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * FML core-mod plugin for Efficient Entities.
 *
 * <h3>Transformer ordering</h3>
 * FML registers each plugin's transformers when the plugin's tweaker runs, and
 * plugins are sorted by {@link IFMLLoadingPlugin.SortingIndex}.  The runtime
 * deobfuscation transformer is registered by {@code FMLDeobfTweaker} at index
 * 1000 (on both Forge and Cleanroom), so a plugin left at the default index 0
 * sees notch-obfuscated vanilla classes in production and
 * {@link EfficientEntitiesTransformer} would never match a vanilla model.
 * Index 1001 places it after deobfuscation, where classes carry MCP names and
 * methods carry SRG names.
 *
 * <h3>Mixin registration</h3>
 * The jar manifest lists the config under {@code MixinConfigs}, which is what
 * MixinBooter 11+ and Cleanroom read.  MixinBooter 10 only asks coremods through
 * {@link IEarlyMixinLoader}, so the config is returned here as well; Mixin skips
 * a config that is already registered, making the overlap harmless.
 */
@IFMLLoadingPlugin.Name("EfficientEntities")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions("com.michaelsebero.efficiententities.asm")
@SuppressWarnings("deprecation") // IEarlyMixinLoader is deprecated from MixinBooter 11 on, but is MixinBooter 10's only route
public class EfficientEntitiesCoremod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    public static final String MIXIN_CONFIG = "mixins.efficiententities.json";

    @Override public String[] getASMTransformerClass()    { return new String[] { EfficientEntitiesTransformer.class.getName() }; }
    @Override public String   getModContainerClass()      { return null; }
    @Override public String   getSetupClass()             { return null; }
    @Override public String   getAccessTransformerClass() { return null; }
    @Override public void     injectData(Map<String, Object> data) { }

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList(MIXIN_CONFIG);
    }
}
