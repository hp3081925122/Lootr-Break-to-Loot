package org.hp.lootr_break_to_loot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.zestyblaze.lootr.api.LootrAPI;
import net.zestyblaze.lootr.api.blockentity.ILootBlockEntity;
import net.zestyblaze.lootr.blocks.LootrBarrelBlock;
import net.zestyblaze.lootr.blocks.LootrChestBlock;
import net.zestyblaze.lootr.blocks.LootrShulkerBlock;
import net.zestyblaze.lootr.blocks.entities.LootrInventoryBlockEntity;
import net.zestyblaze.lootr.config.LootrModConfig;
import net.zestyblaze.lootr.data.DataStorage;
import net.zestyblaze.lootr.data.SpecialChestInventory;
import net.zestyblaze.lootr.registry.LootrAdvancementsInit;
import net.zestyblaze.lootr.registry.LootrStatsInit;
import net.zestyblaze.lootr.util.ChestUtil;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

public final class LootrBreakHandler {
    private static final int VISUAL_SYNC_DELAY_TICKS = 3;
    private static final Style DECAY_STYLE = Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.RED)).withBold(true);
    private static final Style REFRESH_STYLE = Style.EMPTY.withColor(TextColor.fromFormatting(Formatting.BLUE)).withBold(true);
    private final Queue<PendingVisualSync> pendingVisualSyncs = new ArrayDeque<>();

    // 返回 false 表示取消原版方块破坏，返回 true 表示放行后续处理。
    public boolean beforeBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        return !tryHandle(world, player, pos, state, blockEntity);
    }

    private boolean tryHandle(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (world.isClient()
                || !(world instanceof ServerWorld serverWorld)
                || !(player instanceof ServerPlayerEntity serverPlayer)
                || LootrAPI.isFakePlayer(serverPlayer)
                || serverPlayer.isSneaking()
                || serverPlayer.isSpectator()
                || LootrModConfig.get().breaking.enable_break
                || LootrModConfig.isBreakDisabled()
                || !serverWorld.canPlayerModifyAt(serverPlayer, pos)) {
            return false;
        }

        BlockEntity resolvedEntity = blockEntity != null ? blockEntity : serverWorld.getBlockEntity(pos);
        if (!(resolvedEntity instanceof ILootBlockEntity lootBlockEntity)
                || !(resolvedEntity instanceof LootableContainerBlockEntity lootContainer)) {
            return false;
        }

        UUID tileId = lootBlockEntity.getTileId();
        if (tileId == null) {
            serverPlayer.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            serverPlayer.sendMessage(Text.translatable("lootr.message.invalid_block").setStyle(DECAY_STYLE), true);
            return true;
        }

        // 尊重原版容器锁，上锁时播放原版锁定提示并保留方块。
        if (!lootContainer.checkUnlocked(serverPlayer)) {
            serverPlayer.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
            return true;
        }

        SpecialChestInventory inventory;
        if (resolvedEntity instanceof LootrInventoryBlockEntity customInventoryTile) {
            LootrAdvancementsInit.CHEST_PREDICATE.trigger(serverPlayer, tileId);
            DefaultedList<ItemStack> stacks = customInventoryTile.getCustomInventory() != null
                    ? ChestUtil.copyItemList(customInventoryTile.getCustomInventory())
                    : null;
            handleRefreshForCustomInventory(serverWorld, pos, tileId, stacks, serverPlayer, customInventoryTile);
            inventory = DataStorage.getInventory(serverWorld, tileId, stacks, serverPlayer, pos, lootContainer);
        } else {
            if (DataStorage.isDecayed(tileId)) {
                serverWorld.breakBlock(pos, true);
                serverPlayer.sendMessage(Text.translatable("lootr.message.decayed").setStyle(DECAY_STYLE), true);
                DataStorage.removeDecayed(tileId);
                return true;
            }
            handleDecay(serverWorld, tileId, serverPlayer, lootBlockEntity);
            triggerContainerAdvancement(state.getBlock(), serverPlayer, tileId);
            handleRefresh(serverWorld, pos, tileId, serverPlayer, lootBlockEntity);
            inventory = DataStorage.getInventory(serverWorld, tileId, pos, serverPlayer, lootContainer, lootBlockEntity::unpackLootTable);
        }

        if (inventory == null) {
            return false;
        }

        awardScoreIfNeeded(serverPlayer, tileId);

        // 立即持久化开启者状态并同步客户端视觉表现。
        lootBlockEntity.getOpeners().add(serverPlayer.getUuid());
        resolvedEntity.markDirty();
        syncVisualState(serverPlayer, pos, state, resolvedEntity, lootBlockEntity);

        // 掉落当前玩家的独立战利品并触发猪灵仇恨。
        ItemScatterer.spawn(serverWorld, pos, inventory);
        inventory.markDirty();
        PiglinBrain.onGuardedBlockInteracted(serverPlayer, true);

        pendingVisualSyncs.add(new PendingVisualSync(
                serverPlayer.getUuid(),
                serverWorld.getRegistryKey(),
                pos.toImmutable(),
                VISUAL_SYNC_DELAY_TICKS
        ));
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

            ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(pending.playerId());
            ServerWorld targetWorld = server.getWorld(pending.dimension());
            if (serverPlayer == null
                    || serverPlayer.isRemoved()
                    || targetWorld == null
                    || serverPlayer.getWorld() != targetWorld
                    || !targetWorld.isChunkLoaded(pending.pos())) {
                continue;
            }

            BlockEntity blockEntity = targetWorld.getBlockEntity(pending.pos());
            if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)) {
                continue;
            }
            syncVisualState(serverPlayer, pending.pos(), targetWorld.getBlockState(pending.pos()), blockEntity, lootBlockEntity);
        }
    }

    public void clearPending() {
        pendingVisualSyncs.clear();
    }

    private static void syncVisualState(
            ServerPlayerEntity serverPlayer,
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            ILootBlockEntity lootBlockEntity
    ) {
        serverPlayer.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
        Packet<?> bePacket = blockEntity.toUpdatePacket();
        if (bePacket != null) {
            serverPlayer.networkHandler.sendPacket(bePacket);
        }
        lootBlockEntity.updatePacketViaState();
    }

    private static void handleDecay(ServerWorld world, UUID tileId, ServerPlayerEntity player, ILootBlockEntity tile) {
        int decayValue = DataStorage.getDecayValue(tileId);
        if (decayValue > 0 && LootrModConfig.shouldNotify(decayValue)) {
            player.sendMessage(Text.translatable("lootr.message.decay_in", decayValue / 20).setStyle(DECAY_STYLE), true);
        } else if (decayValue == -1 && LootrModConfig.isDecaying(world, tile)) {
            int configuredDecay = LootrModConfig.get().decay.decay_value;
            DataStorage.setDecaying(tileId, configuredDecay);
            player.sendMessage(Text.translatable("lootr.message.decay_start", configuredDecay / 20).setStyle(DECAY_STYLE), true);
        }
    }

    private static void handleRefresh(ServerWorld world, BlockPos pos, UUID tileId, ServerPlayerEntity player, ILootBlockEntity tile) {
        if (DataStorage.isRefreshed(tileId)) {
            DataStorage.refreshInventory(world, pos, tileId, player);
            DataStorage.removeRefreshed(tileId);
            player.sendMessage(Text.translatable("lootr.message.refreshed").setStyle(REFRESH_STYLE), true);
        }
        int refreshValue = DataStorage.getRefreshValue(tileId);
        if (refreshValue > 0 && LootrModConfig.shouldNotify(refreshValue)) {
            player.sendMessage(Text.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(REFRESH_STYLE), true);
        } else if (refreshValue == -1 && LootrModConfig.isRefreshing(world, tile)) {
            int configuredRefresh = LootrModConfig.get().refresh.refresh_value;
            DataStorage.setRefreshing(tileId, configuredRefresh);
            player.sendMessage(Text.translatable("lootr.message.refresh_start", configuredRefresh / 20).setStyle(REFRESH_STYLE), true);
        }
    }

    private static void handleRefreshForCustomInventory(
            ServerWorld world,
            BlockPos pos,
            UUID tileId,
            DefaultedList<ItemStack> stacks,
            ServerPlayerEntity player,
            LootrInventoryBlockEntity tile
    ) {
        if (DataStorage.isRefreshed(tileId)) {
            DataStorage.refreshInventory(world, pos, tileId, stacks, player);
            DataStorage.removeRefreshed(tileId);
            player.sendMessage(Text.translatable("lootr.message.refreshed").setStyle(REFRESH_STYLE), true);
        }
        int refreshValue = DataStorage.getRefreshValue(tileId);
        if (refreshValue > 0 && LootrModConfig.shouldNotify(refreshValue)) {
            player.sendMessage(Text.translatable("lootr.message.refresh_in", refreshValue / 20).setStyle(REFRESH_STYLE), true);
        } else if (refreshValue == -1 && LootrModConfig.isRefreshing(world, tile)) {
            int configuredRefresh = LootrModConfig.get().refresh.refresh_value;
            DataStorage.setRefreshing(tileId, configuredRefresh);
            player.sendMessage(Text.translatable("lootr.message.refresh_start", configuredRefresh / 20).setStyle(REFRESH_STYLE), true);
        }
    }

    private static void triggerContainerAdvancement(Block block, ServerPlayerEntity player, UUID tileId) {
        if (block instanceof LootrBarrelBlock) {
            LootrAdvancementsInit.BARREL_PREDICATE.trigger(player, tileId);
        } else if (block instanceof LootrChestBlock) {
            LootrAdvancementsInit.CHEST_PREDICATE.trigger(player, tileId);
        } else if (block instanceof LootrShulkerBlock) {
            LootrAdvancementsInit.SHULKER_PREDICATE.trigger(player, tileId);
        }
    }

    private static void awardScoreIfNeeded(ServerPlayerEntity player, UUID tileId) {
        if (!DataStorage.isScored(player.getUuid(), tileId)) {
            player.incrementStat(LootrStatsInit.LOOTED_STAT);
            LootrAdvancementsInit.SCORE_PREDICATE.trigger(player, null);
            DataStorage.score(player.getUuid(), tileId);
        }
    }

    private record PendingVisualSync(UUID playerId, RegistryKey<World> dimension, BlockPos pos, int delayTicks) {
    }
}
