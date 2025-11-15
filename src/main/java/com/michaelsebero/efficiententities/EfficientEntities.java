package com.michaelsebero.efficiententities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.michaelsebero.efficiententities.renderer.BatchedModelRenderer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Efficient Entities - A performance mod for Minecraft entity rendering
 * 
 * This mod improves entity rendering performance by:
 * - Batching multiple draw calls into single OpenGL calls
 * - Using CPU-side matrix transformations instead of GPU state changes
 * - Utilizing persistent mapped buffers for efficient data streaming
 * 
 * @author Michael Sebero
 */
@Mod(
    modid = EfficientEntities.MODID,
    name = EfficientEntities.NAME,
    version = EfficientEntities.VERSION,
    acceptableRemoteVersions = "*"
)
public class EfficientEntities {
    
    public static final String MODID = "efficientities";
    public static final String NAME = "Efficient Entities";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);
    
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("Efficient Entities initialized - batched rendering enabled");
    }
    
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            BatchedModelRenderer.getInstance().beginFrame();
        } else {
            BatchedModelRenderer.getInstance().endFrame();
        }
    }
}
