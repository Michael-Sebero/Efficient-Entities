package com.michaelsebero.efficiententities;

import net.minecraftforge.common.config.Config;

@Config(modid = EfficientEntities.MOD_ID)
public final class EEConfig {

    @Config.Comment({
        "Draw entity models through the batched renderer.",
        "Turn off to compare against vanilla rendering; takes effect on the next frame."
    })
    public static boolean enabled = true;

    @Config.Comment({
        "Log renderer statistics every N frames.",
        "0 = disabled"
    })
    @Config.RangeInt(min = 0)
    public static int statisticsLogPeriod = 0;

    private EEConfig() { }
}
