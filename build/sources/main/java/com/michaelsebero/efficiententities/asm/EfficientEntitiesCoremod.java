package com.michaelsebero.efficiententities.asm;

import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("EfficientEntitiesCoremod")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class EfficientEntitiesCoremod implements IFMLLoadingPlugin {
    
    public EfficientEntitiesCoremod() {
        // MixinBooter handles mixin initialization automatically from the manifest
    }
    
    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }
    
    @Override
    public String getModContainerClass() {
        return null;
    }
    
    @Override
    public String getSetupClass() {
        return null;
    }
    
    @Override
    public void injectData(Map<String, Object> data) {
        // No data injection needed
    }
    
    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
