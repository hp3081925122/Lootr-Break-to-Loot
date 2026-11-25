package org.hp.lootr_break_to_loot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.block.entity.LootrDecoratedPotBlockEntity;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public final class LootrBreakHandler {
    private static final int VISUAL_SYNC_DELAY_TICKS = 3;
    private final Queue<PendingVisualSync> pendingVisualSyncs = new ArrayDeque<>();

    // 返回 false 表示取消原版方块破坏，返回 true 表示放行后续处理。
    public boolean beforeBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        return !tryHandle(level, player, pos, state, blockEntity);
    }

    private boolean tryHandle(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)
                || LootrAPI.isFakePlayer(serverPlayer)
                || serverPlayer.isShiftKeyDown()
                || serverPlayer.isSpectator()
                || LootrAPI.isBreakDisabled()
                || LootrAPI.isBreakEnabled()
                || !serverLevel.mayInteract(serverPlayer, pos)) {
            return false;
        }

        BlockEntity resolvedEntity = blockEntity != null ? blockEntity : serverLevel.getBlockEntity(pos);
        ILootrBlockEntity lootBlockEntity = LootrAPI.resolveBlockEntity(resolvedEntity);
        if (lootBlockEntity == null
                || (!lootBlockEntity.hasLootTable() && !lootBlockEntity.isInfoReferenceInventory())
                || !lootBlockEntity.canDropContentsWhenBroken()) {
            return false;
        }

        // 尊重原版容器锁，上锁时提示原版锁定消息并保留方块。
        if (!lootBlockEntity.canPlayerOpen(serverPlayer)) {
            lootBlockEntity.informPlayerCannotOpen(serverPlayer);
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            return true;
        }

        // 饰纹陶罐复用 Lootr 原生破罐逻辑，保留陶片拷贝掉落与半罐动画。
        if (resolvedEntity instanceof LootrDecoratedPotBlockEntity potBlockEntity) {
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            potBlockEntity.dropContent(serverPlayer);
            enqueueVisualSync(serverPlayer, serverLevel, pos);
            return true;
        }

        if (LootrAPI.isDecayed(lootBlockEntity) && lootBlockEntity.canDecay()) {
            lootBlockEntity.performDecay();
            serverPlayer.displayClientMessage(Component.translatable("lootr.message.decayed").setStyle(LootrAPI.getDecayStyle()), true);
            return true;
        }

        if (lootBlockEntity.canDecay()) {
            int decayValue = LootrAPI.getRemainingDecayValue(lootBlockEntity);
            if (decayValue > 0 && LootrAPI.shouldNotify(decayValue)) {
                serverPlayer.displayClientMessage(Component.translatable("lootr.message.decay_in", decayValue / 20).setStyle(LootrAPI.getDecayStyle()), true);
            } else if (decayValue == -1 && LootrAPI.isDecaying(lootBlockEntity)) {
                LootrAPI.setDecaying(lootBlockEntity);
                serverPlayer.displayClientMessage(Component.translatable("lootr.message.decay_start", LootrAPI.getDecayValue() / 20).setStyle(LootrAPI.getDecayStyle()), true);
            }
        }

        lootBlockEntity.performTrigger(serverPlayer);
        boolean shouldUpdate = false;
        if (LootrAPI.isRefreshed(lootBlockEntity) && lootBlockEntity.canRefresh()) {
            lootBlockEntity.performRefresh();
            lootBlockEntity.performClose();
            serverPlayer.displayClientMessage(Component.translatable("lootr.message.refreshed").setStyle(LootrAPI.getRefreshStyle()), true);
            shouldUpdate = true;
        }

        if (lootBlockEntity.canRefresh()) {
            int refreshValue = LootrAPI.getRemainingRefreshValue(lootBlockEntity);
            if (refreshValue > 0 && LootrAPI.shouldNotify(refreshValue)) {
                serverPlayer.displayClientMessage(Component.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(LootrAPI.getRefreshStyle()), true);
            } else if (refreshValue == -1 && LootrAPI.isRefreshing(lootBlockEntity)) {
                LootrAPI.setRefreshing(lootBlockEntity);
                serverPlayer.displayClientMessage(Component.translatable("lootr.message.refresh_start", LootrAPI.getRefreshValue() / 20).setStyle(LootrAPI.getRefreshStyle()), true);
            }
        }

        ILootrInventory inventory = LootrAPI.getInventory(lootBlockEntity, serverPlayer);
        if (inventory == null) {
            return false;
        }

        if (!lootBlockEntity.hasServerOpened(serverPlayer)) {
            serverPlayer.awardStat(LootrRegistry.getLootedStat());
            LootrRegistry.getStatTrigger().trigger(serverPlayer);
        }
        if (!lootBlockEntity.hasBeenOpened()) {
            lootBlockEntity.setHasBeenOpened(true);
            shouldUpdate = true;
        }
        if (lootBlockEntity.addOpener(serverPlayer)) {
            shouldUpdate = true;
        }
        if (shouldUpdate) {
            lootBlockEntity.performUpdate(serverPlayer);
        }
        syncVisualState(serverPlayer, pos, state, lootBlockEntity);

        Containers.dropContents(serverLevel, pos, inventory);
        inventory.setChanged();
        if (LootrAPI.isCustomTrapped() && lootBlockEntity.isInfoReferenceInventory()) {
            Block block = state.getBlock();
            serverLevel.updateNeighborsAt(pos, block);
            serverLevel.updateNeighborsAt(pos.below(), block);
        }
        PiglinAi.angerNearbyPiglins(serverPlayer, true);

        enqueueVisualSync(serverPlayer, serverLevel, pos);
        return true;
    }

    public void onServerTick(MinecraftServer server) {
        if (pendingVisualSyncs.isEmpty()) {
            return;
        }
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

            ILootrBlockEntity lootBlockEntity = LootrAPI.resolveBlockEntity(targetLevel.getBlockEntity(pending.pos()));
            if (lootBlockEntity == null) {
                continue;
            }
            syncVisualState(serverPlayer, pending.pos(), targetLevel.getBlockState(pending.pos()), lootBlockEntity);
        }
    }

    public void clearPending() {
        pendingVisualSyncs.clear();
    }

    private void enqueueVisualSync(ServerPlayer serverPlayer, ServerLevel serverLevel, BlockPos pos) {
        pendingVisualSyncs.add(new PendingVisualSync(
                serverPlayer.getUUID(),
                serverLevel.dimension(),
                pos.immutable(),
                VISUAL_SYNC_DELAY_TICKS
        ));
    }

    private static void syncVisualState(ServerPlayer serverPlayer, BlockPos pos, BlockState state, ILootrBlockEntity lootBlockEntity) {
        serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        lootBlockEntity.updatePacketViaForce();
        lootBlockEntity.performOpen(serverPlayer);
    }

    private record PendingVisualSync(UUID playerId, ResourceKey<Level> dimension, BlockPos pos, int delayTicks) {
    }
}
