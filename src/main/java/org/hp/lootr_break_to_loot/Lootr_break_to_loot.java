package org.hp.lootr_break_to_loot;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.hp.lootr_break_to_loot.network.NetworkHandler;

@Mod(Lootr_break_to_loot.MODID)
public class Lootr_break_to_loot {
    public static final String MODID = "lootr_break_to_loot";

    public Lootr_break_to_loot() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(new LootrBreakHandler());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::registerMessages);
    }
}
