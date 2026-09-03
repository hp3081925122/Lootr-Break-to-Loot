package org.hp.lootr_break_to_loot;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.zestyblaze.lootr.api.blockentity.ILootBlockEntity;
import net.zestyblaze.lootr.config.LootrModConfig;
import net.zestyblaze.lootr.data.DataStorage;
import net.zestyblaze.lootr.data.SpecialChestInventory;
import org.hp.lootr_break_to_loot.network.NetworkHandler;

public final class LootrBreakHandler {
    private LootrBreakHandler() {
    }

    public static boolean handle(ServerPlayerEntity player, BlockPos pos) {
        World world = player.getWorld();
        if (world.isClient || !(world instanceof ServerWorld serverWorld) || player.isSneaking() || player.isSpectator()
                || LootrModConfig.get().breaking.enable_break || LootrModConfig.isBreakDisabled()) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof ILootBlockEntity lootBlockEntity)
                || !(blockEntity instanceof LootableContainerBlockEntity lootContainer)) {
            return false;
        }

        SpecialChestInventory inventory = DataStorage.getInventory(world, lootBlockEntity.getTileId(), pos, player,
                lootContainer, lootBlockEntity::unpackLootTable);
        if (inventory == null) {
            return false;
        }

        lootBlockEntity.getOpeners().add(player.getUuid());
        blockEntity.markDirty();
        lootBlockEntity.updatePacketViaState();
        NetworkHandler.sendLootrOpened(player, pos);
        ItemScatterer.spawn(serverWorld, pos, inventory);
        inventory.markDirty();
        return true;
    }
}
