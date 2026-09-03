package org.hp.lootr_break_to_loot.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class NetworkHandler {
    public static final Identifier LOOTR_OPENED_ID = new Identifier("lootr_break_to_loot", "lootr_opened");

    private NetworkHandler() {
    }

    public static void sendLootrOpened(ServerPlayerEntity player, BlockPos pos) {
        PacketByteBuf buffer = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeBlockPos(pos);
        ServerPlayNetworking.send(player, LOOTR_OPENED_ID, buffer);
    }
}
