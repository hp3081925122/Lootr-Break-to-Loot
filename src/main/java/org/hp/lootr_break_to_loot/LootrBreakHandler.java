package org.hp.lootr_break_to_loot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import noobanidus.mods.lootr.advancement.GenericTrigger;
import noobanidus.mods.lootr.api.LootrAPI;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.block.LootrShulkerBlock;
import noobanidus.mods.lootr.block.entities.LootrInventoryBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.data.DataStorage;
import noobanidus.mods.lootr.data.SpecialChestInventory;
import noobanidus.mods.lootr.init.ModAdvancements;
import noobanidus.mods.lootr.init.ModStats;
import noobanidus.mods.lootr.network.PacketHandler;
import noobanidus.mods.lootr.network.UpdateModelData;
import noobanidus.mods.lootr.util.ChestUtil;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public final class LootrBreakHandler {
    private static final int VISUAL_SYNC_DELAY_TICKS = 3;
    private final Queue<PendingVisualSync> pendingVisualSyncs = new ArrayDeque<>();

    // 在高优先级领地保护之后、Lootr 默认破坏拦截之前接管合法的非潜行破坏。
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel serverLevel) || serverLevel.isClientSide()) {
            return;
        }
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)
                || serverPlayer instanceof FakePlayer
                || LootrAPI.isFakePlayer(serverPlayer)
                || serverPlayer.isShiftKeyDown()
                || serverPlayer.isSpectator()
                || ConfigManager.DISABLE_BREAK.get()
                || ConfigManager.ENABLE_BREAK.get()) {
            return;
        }

        BlockPos pos = event.getPos();
        if (!serverLevel.mayInteract(serverPlayer, pos)) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)
                || !(blockEntity instanceof RandomizableContainerBlockEntity lootContainer)) {
            return;
        }

        BlockState state = event.getState();
        UUID tileId = lootBlockEntity.getTileId();
        if (tileId == null) {
            event.setCanceled(true);
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            serverPlayer.displayClientMessage(Component.translatable("lootr.message.invalid_block").setStyle(ChestUtil.getInvalidStyle()), true);
            return;
        }

        // 尊重原版容器锁，上锁时播放原版锁定提示并保留方块。
        if (!lootContainer.canOpen(serverPlayer)) {
            event.setCanceled(true);
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            return;
        }

        SpecialChestInventory inventory;
        if (blockEntity instanceof LootrInventoryBlockEntity customInventoryTile) {
            ModAdvancements.CHEST_PREDICATE.trigger(serverPlayer, tileId);
            NonNullList<ItemStack> stacks = customInventoryTile.getCustomInventory() != null
                    ? ChestUtil.copyItemList(customInventoryTile.getCustomInventory())
                    : null;
            handleRefreshForCustomInventory(serverLevel, pos, tileId, stacks, serverPlayer, customInventoryTile);
            inventory = DataStorage.getInventory(serverLevel, tileId, stacks, serverPlayer, pos, lootContainer);
        } else {
            if (DataStorage.isDecayed(tileId)) {
                event.setCanceled(true);
                serverLevel.destroyBlock(pos, true);
                serverPlayer.displayClientMessage(Component.translatable("lootr.message.decayed").setStyle(ChestUtil.getDecayStyle()), true);
                DataStorage.removeDecayed(tileId);
                return;
            }
            handleDecay(serverLevel, tileId, serverPlayer, lootBlockEntity);
            triggerContainerAdvancement(state.getBlock(), serverPlayer, tileId);
            handleRefresh(serverLevel, pos, tileId, serverPlayer, lootBlockEntity);
            inventory = DataStorage.getInventory(serverLevel, tileId, pos, serverPlayer, lootContainer, lootBlockEntity::unpackLootTable);
        }

        if (inventory == null) {
            return;
        }

        awardScoreIfNeeded(serverPlayer, tileId);

        // 取消方块破坏并立即持久化开启者状态，避免断线或卸载丢失。
        event.setCanceled(true);
        lootBlockEntity.getOpeners().add(serverPlayer.getUUID());
        blockEntity.setChanged();
        syncVisualState(serverPlayer, pos, state, blockEntity, lootBlockEntity);

        // 掉落当前玩家的独立战利品并触发猪灵仇恨。
        Containers.dropContents(serverLevel, pos, inventory);
        inventory.setChanged();
        PiglinAi.angerNearbyPiglins(serverPlayer, true);

        pendingVisualSyncs.add(new PendingVisualSync(
                serverPlayer.getUUID(),
                serverLevel.dimension(),
                pos.immutable(),
                VISUAL_SYNC_DELAY_TICKS
        ));
    }

    // 延迟复发视觉同步包，覆盖客户端预测破坏恢复后的方块实体与木桶模型缓存。
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || pendingVisualSyncs.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int pendingCount = pendingVisualSyncs.size();
        for (int index = 0; index < pendingCount; index++) {
            PendingVisualSync pending = pendingVisualSyncs.poll();
            if (pending == null) {
                return;
            }
            if (pending.delayTicks() > 0) {
                pendingVisualSyncs.add(new PendingVisualSync(
                        pending.playerId(),
                        pending.dimension(),
                        pending.pos(),
                        pending.delayTicks() - 1
                ));
                continue;
            }

            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(pending.playerId());
            ServerLevel targetLevel = server.getLevel(pending.dimension());
            if (serverPlayer == null
                    || serverPlayer.isRemoved()
                    || serverPlayer.hasDisconnected()
                    || targetLevel == null
                    || serverPlayer.level() != targetLevel
                    || !targetLevel.isLoaded(pending.pos())) {
                continue;
            }

            BlockEntity blockEntity = targetLevel.getBlockEntity(pending.pos());
            if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)) {
                continue;
            }
            syncVisualState(serverPlayer, pending.pos(), targetLevel.getBlockState(pending.pos()), blockEntity, lootBlockEntity);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        pendingVisualSyncs.clear();
    }

    private static void syncVisualState(
            ServerPlayer serverPlayer,
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            ILootBlockEntity lootBlockEntity
    ) {
        serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        lootBlockEntity.updatePacketViaForce(blockEntity);
        lootBlockEntity.updatePacketViaState();
        PacketHandler.sendToInternal(new UpdateModelData(pos), serverPlayer);
    }

    private static void handleDecay(ServerLevel level, UUID tileId, ServerPlayer player, ILootBlockEntity tile) {
        int decayValue = DataStorage.getDecayValue(tileId);
        if (decayValue > 0 && ConfigManager.shouldNotify(decayValue)) {
            player.displayClientMessage(Component.translatable("lootr.message.decay_in", decayValue / 20).setStyle(ChestUtil.getDecayStyle()), true);
        } else if (decayValue == -1 && ConfigManager.isDecaying(level, tile)) {
            int configuredDecay = ConfigManager.DECAY_VALUE.get();
            DataStorage.setDecaying(tileId, configuredDecay);
            player.displayClientMessage(Component.translatable("lootr.message.decay_start", configuredDecay / 20).setStyle(ChestUtil.getDecayStyle()), true);
        }
    }

    private static void handleRefresh(ServerLevel level, BlockPos pos, UUID tileId, ServerPlayer player, ILootBlockEntity tile) {
        if (DataStorage.isRefreshed(tileId)) {
            DataStorage.refreshInventory(level, pos, tileId, player);
            DataStorage.removeRefreshed(tileId);
            player.displayClientMessage(Component.translatable("lootr.message.refreshed").setStyle(ChestUtil.getRefreshStyle()), true);
        }
        int refreshValue = DataStorage.getRefreshValue(tileId);
        if (refreshValue > 0 && ConfigManager.shouldNotify(refreshValue)) {
            player.displayClientMessage(Component.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(ChestUtil.getRefreshStyle()), true);
        } else if (refreshValue == -1 && ConfigManager.isRefreshing(level, tile)) {
            int configuredRefresh = ConfigManager.REFRESH_VALUE.get();
            DataStorage.setRefreshing(tileId, configuredRefresh);
            player.displayClientMessage(Component.translatable("lootr.message.refresh_start", configuredRefresh / 20).setStyle(ChestUtil.getRefreshStyle()), true);
        }
    }

    private static void handleRefreshForCustomInventory(
            ServerLevel level,
            BlockPos pos,
            UUID tileId,
            NonNullList<ItemStack> stacks,
            ServerPlayer player,
            LootrInventoryBlockEntity tile
    ) {
        if (DataStorage.isRefreshed(tileId)) {
            DataStorage.refreshInventory(level, pos, tileId, stacks, player);
            DataStorage.removeRefreshed(tileId);
            player.displayClientMessage(Component.translatable("lootr.message.refreshed").setStyle(ChestUtil.getRefreshStyle()), true);
        }
        int refreshValue = DataStorage.getRefreshValue(tileId);
        if (refreshValue > 0 && ConfigManager.shouldNotify(refreshValue)) {
            player.displayClientMessage(Component.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(ChestUtil.getRefreshStyle()), true);
        } else if (refreshValue == -1 && ConfigManager.isRefreshing(level, tile)) {
            int configuredRefresh = ConfigManager.REFRESH_VALUE.get();
            DataStorage.setRefreshing(tileId, configuredRefresh);
            player.displayClientMessage(Component.translatable("lootr.message.refresh_start", configuredRefresh / 20).setStyle(ChestUtil.getRefreshStyle()), true);
        }
    }

    private static void triggerContainerAdvancement(Block block, ServerPlayer player, UUID tileId) {
        GenericTrigger<UUID> trigger = ModAdvancements.CHEST_PREDICATE;
        if (block instanceof BarrelBlock) {
            trigger = ModAdvancements.BARREL_PREDICATE;
        } else if (block instanceof LootrShulkerBlock) {
            trigger = ModAdvancements.SHULKER_PREDICATE;
        }
        trigger.trigger(player, tileId);
    }

    private static void awardScoreIfNeeded(ServerPlayer player, UUID tileId) {
        if (!DataStorage.isScored(player.getUUID(), tileId)) {
            player.awardStat(ModStats.LOOTED_STAT);
            ModAdvancements.SCORE_PREDICATE.trigger(player, null);
            DataStorage.score(player.getUUID(), tileId);
        }
    }

    private record PendingVisualSync(UUID playerId, ResourceKey<Level> dimension, BlockPos pos, int delayTicks) {
    }
}
