package cn.piq.fcarcade.world;

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
import net.minecraft.server.level.ServerLevel;
import java.util.UUID;

/** The cabinet anchor owns one persistent assembly UUID, not home-console cartridge/link state. */
public final class DualCabinetBlockEntity extends LegacyFcArcadeBlockEntity {
    private UUID assemblyId = UUID.randomUUID();
    private boolean installed;
    private int maintenanceTicks;
    public DualCabinetBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.DUAL_CABINET.get(), pos, state); }
    public UUID assemblyId() { return assemblyId; }
    public boolean installed() { return installed; }
    public boolean compactFootprint() { return getBlockState().getValue(DualCabinetBlock.COMPACT); }
    void install() {
        installed = true; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }
    public void maintenanceTick() {
        if (maintenanceTicks++ % 20 == 0) DualCabinetStructure.reconcile(this);
    }
    @Override public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) setChanged();
    }
    @Override public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel server) DualCabinetStructure.stopSession(server, worldPosition, assemblyId);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("AssemblyId")) assemblyId = tag.getUUID("AssemblyId");
        installed = tag.getBoolean("Installed");
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("AssemblyId", assemblyId); tag.putBoolean("Installed", installed);
    }
    // Inherit the base update tag: disk NBT alone omits power, cable and game-profile visuals.
    // Base saveWithoutMetadata still dispatches to our saveAdditional for assembly/installed data.
    @Override public boolean onlyOpCanSetNbt() { return true; }
}
