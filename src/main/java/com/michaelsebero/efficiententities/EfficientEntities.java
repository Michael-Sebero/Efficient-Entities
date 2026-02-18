package com.michaelsebero.efficiententities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

@Mod(modid = EfficientEntities.MOD_ID, name = EfficientEntities.MOD_NAME, version = EfficientEntities.VERSION, acceptableRemoteVersions = "*")
public class EfficientEntities {

    public static final String MOD_ID   = "efficiententities";
    public static final String MOD_NAME = "Efficient Entities";
    public static final String VERSION  = "V1";

    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @EventHandler
    public void onConstruction(FMLConstructionEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == Phase.START) {
            EfficientModelRenderer.instance().beginFrame();
        } else {
            EfficientModelRenderer.instance().finishFrame();
        }
    }
}
