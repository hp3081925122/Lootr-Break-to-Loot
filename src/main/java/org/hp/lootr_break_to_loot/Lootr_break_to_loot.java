package org.hp.lootr_break_to_loot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.resources.ResourceLocation;

public final class Lootr_break_to_loot implements ModInitializer {
    public static final String MODID = "lootr_break_to_loot";

    @Override
    public void onInitialize() {
        LootrBreakHandler handler = new LootrBreakHandler();
        ResourceLocation earlyPhase = ResourceLocation.fromNamespaceAndPath(MODID, "early");
        PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(earlyPhase, Event.DEFAULT_PHASE);
        PlayerBlockBreakEvents.BEFORE.register(earlyPhase, handler::beforeBlockBreak);
        ServerTickEvents.START_SERVER_TICK.register(handler::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> handler.clearPending());
    }
}
