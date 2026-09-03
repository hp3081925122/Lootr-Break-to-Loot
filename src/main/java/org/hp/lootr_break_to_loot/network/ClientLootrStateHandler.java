package org.hp.lootr_break_to_loot.network;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.zestyblaze.lootr.api.blockentity.ILootBlockEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ClientLootrStateHandler {
    private static final int MAX_RETRY_TICKS = 20;
    private static final Map<BlockPos, Integer> PENDING_POSITIONS = new HashMap<>();

    private ClientLootrStateHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientLootrStateHandler::tick);
    }

    public static void apply(MinecraftClient client, BlockPos pos) {
        if (!tryApply(client, pos)) {
            PENDING_POSITIONS.put(pos.toImmutable(), MAX_RETRY_TICKS);
        }
    }

    private static void tick(MinecraftClient client) {
        if (PENDING_POSITIONS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<BlockPos, Integer>> iterator = PENDING_POSITIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (tryApply(client, entry.getKey())) {
                iterator.remove();
                continue;
            }

            int remainingTicks = entry.getValue() - 1;
            if (remainingTicks <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remainingTicks);
            }
        }
    }

    private static boolean tryApply(MinecraftClient client, BlockPos pos) {
        ClientWorld world = client.world;
        if (world == null) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)) {
            return false;
        }

        lootBlockEntity.setOpened(true);
        return true;
    }
}
