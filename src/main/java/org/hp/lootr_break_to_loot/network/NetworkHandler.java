package org.hp.lootr_break_to_loot.network;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.hp.lootr_break_to_loot.Lootr_break_to_loot;

import java.util.Optional;

public final class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Lootr_break_to_loot.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {
    }

    // 注册服务端向客户端同步 Lootr 开启状态的数据包。
    public static void registerMessages() {
        CHANNEL.registerMessage(
                0,
                LootrOpenedPacket.class,
                LootrOpenedPacket::encode,
                LootrOpenedPacket::decode,
                LootrOpenedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendLootrOpened(ServerPlayer player, BlockPos pos) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new LootrOpenedPacket(pos));
    }
}
