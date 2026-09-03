package org.hp.lootr_break_to_loot.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class LootrOpenedPacket {
    private final BlockPos pos;

    public LootrOpenedPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(LootrOpenedPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
    }

    public static LootrOpenedPacket decode(FriendlyByteBuf buffer) {
        return new LootrOpenedPacket(buffer.readBlockPos());
    }

    // 在客户端线程应用 Lootr 箱子的已开启状态。
    public static void handle(LootrOpenedPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> ClientLootrStateHandler.apply(packet.pos)
        ));
        context.setPacketHandled(true);
    }
}
