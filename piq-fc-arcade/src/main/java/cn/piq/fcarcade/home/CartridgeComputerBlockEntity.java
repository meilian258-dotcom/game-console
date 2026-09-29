package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.server.ServerCartridgeService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** Identity only: the editable cartridge always stays in the player's hand. */
public final class CartridgeComputerBlockEntity extends BlockEntity {
    private UUID computerId = UUID.randomUUID();
    public CartridgeComputerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CARTRIDGE_COMPUTER.get(), pos, state);
    }
    public UUID computerId() { return computerId; }
    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) setChanged();
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("ComputerId") && !tag.getUUID("ComputerId").equals(new UUID(0, 0)))
            computerId = tag.getUUID("ComputerId");
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("ComputerId", computerId);
    }
    @Override public void setRemoved() {
        super.setRemoved();
        invalidateEditors();
    }
    @Override public void onChunkUnloaded() {
        invalidateEditors();
        super.onChunkUnloaded();
    }
    private void invalidateEditors() {
        if (level instanceof ServerLevel serverLevel)
            ServerCartridgeService.computerRemoved(serverLevel.getServer(), this);
    }
    @Override public boolean onlyOpCanSetNbt() { return true; }
}
