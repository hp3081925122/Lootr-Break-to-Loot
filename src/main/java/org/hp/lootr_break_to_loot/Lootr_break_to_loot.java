package org.hp.lootr_break_to_loot;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(Lootr_break_to_loot.MODID)
public final class Lootr_break_to_loot {
    public static final String MODID = "lootr_break_to_loot";

    public Lootr_break_to_loot(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(new LootrBreakHandler());
    }
}
