package org.hp.lootr_break_to_loot.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.hp.lootr_break_to_loot.Lootr_break_to_loot;

public final class NetworkHandler {
    public static final Identifier LOOTR_OPENED_ID = new Identifier(Lootr_break_to_loot.MOD_ID, "lootr_opened");

    private NetworkHandler() {
    }

    public static void sendLootrOpened(ServerPlayerEntity player, BlockPos pos) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeBlockPos(pos);
        ServerPlayNetworking.send(player, LOOTR_OPENED_ID, buffer);
    }
}
