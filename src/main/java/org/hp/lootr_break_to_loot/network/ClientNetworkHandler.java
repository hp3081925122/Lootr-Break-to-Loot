package org.hp.lootr_break_to_loot.network;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

public final class ClientNetworkHandler implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLootrStateHandler.register();
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LOOTR_OPENED_ID,
                (client, handler, buffer, responseSender) -> {
                    BlockPos pos = buffer.readBlockPos();
                    client.execute(() -> ClientLootrStateHandler.apply(client, pos));
                });
    }
}
