package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

public final class WideLcdTvPartBlockEntity extends BlockEntity {
    private UUID owner;
    private BlockPos anchor;
    private int maintenanceTicks;

    public WideLcdTvPartBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIDE_LCD_TV_PART.get(), pos, state);
    }

    public UUID owner() { return owner; }
    public BlockPos anchor() { return anchor; }

    void attach(UUID id, BlockPos pos) {
        owner = id; anchor = pos.immutable(); setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    void maintenanceTick() {
        if (maintenanceTicks++ % 20 == 0) WideLcdTvStructure.reconcile(this);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        anchor = tag.contains("Anchor") ? BlockPos.of(tag.getLong("Anchor")) : null;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null && anchor != null) { tag.putUUID("Owner", owner); tag.putLong("Anchor", anchor.asLong()); }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public boolean onlyOpCanSetNbt() { return true; }
}
