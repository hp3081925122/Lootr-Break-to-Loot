package org.hp.lootr_break_to_loot.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import org.hp.lootr_break_to_loot.Lootr_break_to_loot;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

// 客户端方块实体可能在服务端纠正方块状态前短暂缺失，因此在客户端线程重试同步。
@Mod.EventBusSubscriber(modid = Lootr_break_to_loot.MODID, value = Dist.CLIENT)
public final class ClientLootrStateHandler {
    private static final int MAX_RETRY_TICKS = 20;
    private static final Map<BlockPos, Integer> PENDING_POSITIONS = new HashMap<>();

    private ClientLootrStateHandler() {
    }

    public static void apply(BlockPos pos) {
        if (tryApply(pos)) {
            return;
        }

        PENDING_POSITIONS.put(pos.immutable(), MAX_RETRY_TICKS);
    }

    // 每个客户端刻重试一次，等待服务端取消破坏后的方块实体重新出现在客户端。
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_POSITIONS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<BlockPos, Integer>> iterator = PENDING_POSITIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            if (tryApply(entry.getKey())) {
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

    // 优先使用客户端区块的立即创建路径，避免只读取到客户端预测破坏后的空方块实体。
    private static boolean tryApply(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        BlockEntity blockEntity = minecraft.level.getChunkAt(pos)
                .getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)) {
            return false;
        }

        lootBlockEntity.setOpened(true);
        return true;
    }
}
