package org.hp.lootr_break_to_loot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.data.DataStorage;
import noobanidus.mods.lootr.data.SpecialChestInventory;
import org.hp.lootr_break_to_loot.network.NetworkHandler;

public final class LootrBreakHandler {
    // 在 Lootr 的破坏限制处理之前接管非潜行破坏。
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (!(event.getLevel() instanceof Level level)
                || level.isClientSide()
                || player.isShiftKeyDown()
                || player.isSpectator()
                || ConfigManager.DISABLE_BREAK.get()
                || ConfigManager.ENABLE_BREAK.get()) {
            return;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)
                || !(blockEntity instanceof RandomizableContainerBlockEntity lootContainer)) {
            return;
        }

        // 通过 Lootr 的 DataStorage 获取当前玩家的独立战利品库存。
        SpecialChestInventory inventory = DataStorage.getInventory(
                level,
                lootBlockEntity.getTileId(),
                pos,
                serverPlayer,
                lootContainer,
                lootBlockEntity::unpackLootTable
        );
        if (inventory == null) {
            return;
        }

        // 取消原版移除方块流程，使 Lootr 箱子保留在原处。
        event.setCanceled(true);

        // 同步 Lootr 的开启状态和开启者记录，让当前玩家看到已开启外观。
        lootBlockEntity.getOpeners().add(serverPlayer.getUUID());
        blockEntity.setChanged();
        NetworkHandler.sendLootrOpened(serverPlayer, pos);

        // 使用原版容器掉落算法，在箱子位置生成 ItemEntity，并消耗该玩家的独立库存。
        Containers.dropContents(level, pos, inventory);
        inventory.setChanged();
    }
}
