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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.LootrRegistry;
import noobanidus.mods.lootr.common.api.data.ILootrInventoryStore;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.interfaces.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.block.entity.LootrDecoratedPotBlockEntity;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public final class LootrBreakHandler {
    private static final int VISUAL_SYNC_DELAY_TICKS = 3;
    private final Queue<PendingVisualSync> pendingVisualSyncs = new ArrayDeque<>();

    // 在高优先级领地保护之后、Lootr 默认破坏拦截之前接管合法的非潜行破坏。
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBlockBreak(BreakBlockEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel serverLevel) || serverLevel.isClientSide()) {
            return;
        }
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer serverPlayer)
                || LootrAPI.isFakePlayer(serverPlayer)
                || serverPlayer.isShiftKeyDown()
                || serverPlayer.isSpectator()
                || LootrAPI.isBreakDisabled()
                || LootrAPI.isBreakEnabled()) {
            return;
        }

        BlockPos pos = event.getPos();
        if (!serverLevel.mayInteract(serverPlayer, pos)) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        ILootrBlockEntity lootBlockEntity = LootrAPI.wrapBlockEntity(blockEntity);
        if (lootBlockEntity == null
                || (!lootBlockEntity.hasLootTable() && !lootBlockEntity.isDataReferenceInventory())
                || !lootBlockEntity.canDropContentsWhenBroken()) {
            return;
        }

        BlockState state = event.getState();
        // 尊重原版容器锁，上锁时提示原版锁定消息并保留方块。
        if (!lootBlockEntity.canPlayerOpen(serverPlayer)) {
            event.setCanceled(true);
            event.setNotifyClient(true);
            lootBlockEntity.informPlayerCannotOpen(serverPlayer);
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            return;
        }

        // 饰纹陶罐复用 Lootr 原生破罐逻辑，保留陶片拷贝掉落与半罐动画。
        if (blockEntity instanceof LootrDecoratedPotBlockEntity potBlockEntity) {
            event.setCanceled(true);
            event.setNotifyClient(true);
            serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
            potBlockEntity.dropContent(serverPlayer);
            enqueueVisualSync(serverPlayer, serverLevel, pos);
            return;
        }

        ILootrInventoryStore store = LootrAPI.getData(lootBlockEntity);
        if (store == null) {
            return;
        }

        if (lootBlockEntity.canDecay()) {
            if (store.isDecayed()) {
                event.setCanceled(true);
                event.setNotifyClient(true);
                lootBlockEntity.performDecay();
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.decayed").setStyle(LootrAPI.getDecayStyle()));
                return;
            }
            int decayValue = store.remainingDecayTime();
            if (decayValue > 0 && LootrAPI.shouldNotify(decayValue)) {
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.decay_in", decayValue / 20).setStyle(LootrAPI.getDecayStyle()));
            } else if (decayValue == -1 && LootrAPI.shouldBeginDecaying(lootBlockEntity)) {
                store.beginDecay();
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.decay_start", LootrAPI.getDecayValue() / 20).setStyle(LootrAPI.getDecayStyle()));
            }
        }

        lootBlockEntity.performTrigger(serverPlayer);
        boolean shouldUpdate = false;
        if (lootBlockEntity.canRefresh()) {
            if (store.isRefreshed()) {
                store.performRefresh();
                lootBlockEntity.performClose();
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.refreshed").setStyle(LootrAPI.getRefreshStyle()));
                shouldUpdate = true;
            }
            int refreshValue = store.remainingRefreshTime();
            if (refreshValue > 0 && LootrAPI.shouldNotify(refreshValue)) {
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(LootrAPI.getRefreshStyle()));
            } else if (refreshValue == -1 && LootrAPI.shouldBeginRefreshing(lootBlockEntity)) {
                store.beginRefresh();
                serverPlayer.sendOverlayMessage(Component.translatable("lootr.message.refresh_start", LootrAPI.getRefreshValue() / 20).setStyle(LootrAPI.getRefreshStyle()));
            }
        }

        ILootrInventory inventory = LootrAPI.getInventory(lootBlockEntity, serverPlayer);
        if (inventory == null) {
            return;
        }

        if (!lootBlockEntity.hasServerOpened(serverPlayer)) {
            serverPlayer.awardStat(LootrRegistry.getLootedStat());
            LootrRegistry.getStatTrigger().trigger(serverPlayer);
        }

        event.setCanceled(true);
        event.setNotifyClient(true);
        if (lootBlockEntity.addOpener(serverPlayer)) {
            shouldUpdate = true;
        }
        if (shouldUpdate) {
            lootBlockEntity.performUpdate(serverPlayer);
        }
        syncVisualState(serverPlayer, pos, state, lootBlockEntity);

        Containers.dropContents(serverLevel, pos, inventory);
        inventory.setChanged();
        if (LootrAPI.isCustomTrapped() && lootBlockEntity.isDataReferenceInventory()) {
            Block block = state.getBlock();
            serverLevel.updateNeighborsAt(pos, block, null);
            serverLevel.updateNeighborsAt(pos.below(), block, null);
        }
        PiglinAi.angerNearbyPiglins(serverLevel, serverPlayer, true);

        enqueueVisualSync(serverPlayer, serverLevel, pos);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Pre event) {
        if (pendingVisualSyncs.isEmpty()) {
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

            ILootrBlockEntity lootBlockEntity = LootrAPI.wrapBlockEntity(targetLevel.getBlockEntity(pending.pos()));
            if (lootBlockEntity == null) {
                continue;
            }
            syncVisualState(serverPlayer, pending.pos(), targetLevel.getBlockState(pending.pos()), lootBlockEntity);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
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
