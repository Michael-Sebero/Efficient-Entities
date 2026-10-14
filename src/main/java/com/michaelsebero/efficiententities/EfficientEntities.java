package com.michaelsebero.efficiententities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = EfficientEntities.MOD_ID, name = EfficientEntities.MOD_NAME, version = Tags.VERSION,
     clientSideOnly = true, acceptableRemoteVersions = "*")
public class EfficientEntities {

    public static final String MOD_ID   = "efficiententities";
    public static final String MOD_NAME = "Efficient Entities";

    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.EventBusSubscriber(modid = MOD_ID, value = Side.CLIENT)
    public static final class ClientEvents {

        private ClientEvents() { }

        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == Phase.START) {
                EfficientModelRenderer.beginFrame();
            } else {
                EfficientModelRenderer.finishFrame();
            }
        }

        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (MOD_ID.equals(event.getModID())) {
                ConfigManager.sync(MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
