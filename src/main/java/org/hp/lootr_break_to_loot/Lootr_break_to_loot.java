package org.hp.lootr_break_to_loot;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(Lootr_break_to_loot.MODID)
public final class Lootr_break_to_loot {
    public static final String MODID = "lootr_break_to_loot";

    public Lootr_break_to_loot() {
        MinecraftForge.EVENT_BUS.register(new LootrBreakHandler());
    }
}
