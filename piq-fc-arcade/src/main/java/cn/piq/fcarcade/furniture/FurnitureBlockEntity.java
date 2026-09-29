package cn.piq.fcarcade.furniture;

import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class FurnitureBlockEntity extends BlockEntity {
    private UUID identity=UUID.randomUUID();
    private int ticks;
    public FurnitureBlockEntity(BlockPos pos,BlockState state) { super(FurnitureRegistry.FURNITURE_ENTITY.get(),pos,state); }
    public UUID identity() { return identity; }
    void identity(UUID identity) { this.identity=identity;setChanged(); }
    public void maintenanceTick() { if(++ticks%20==0&&level instanceof ServerLevel server) FurnitureService.reconcile(server,this); }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registry) { super.saveAdditional(tag,registry);tag.putUUID("FurnitureId",identity); }
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registry) { super.loadAdditional(tag,registry);if(tag.hasUUID("FurnitureId"))identity=tag.getUUID("FurnitureId"); }
    @Override public boolean onlyOpCanSetNbt() { return true; }
    @Override public void onChunkUnloaded() { if(level instanceof ServerLevel server)FurnitureService.eject(server,worldPosition,identity);super.onChunkUnloaded(); }
}
