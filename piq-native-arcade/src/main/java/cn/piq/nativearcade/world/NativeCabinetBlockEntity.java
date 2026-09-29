// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.world;

import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Persistent assembly identity only. Core state, ROMs and input are separately owned by the native client. */
public final class NativeCabinetBlockEntity extends BlockEntity {
    private UUID assemblyId=UUID.randomUUID();
    private boolean installed;
    private int maintenanceTicks;
    public NativeCabinetBlockEntity(BlockPos pos,BlockState state){super(NativeArcadeRegistries.CABINET_ENTITY.get(),pos,state);}
    public UUID assemblyId(){return assemblyId;}
    public boolean installed(){return installed;}
    void install(){installed=true;setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);}
    public void maintenanceTick(){if(maintenanceTicks++%20==0)NativeCabinetStructure.reconcile(this);}
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide)setChanged();}
    @Override public void onChunkUnloaded(){if(level instanceof ServerLevel server)NativeCabinetStructure.stopSession(server,worldPosition,assemblyId);}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);if(tag.hasUUID("AssemblyId"))assemblyId=tag.getUUID("AssemblyId");installed=tag.getBoolean("Installed");}
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.putUUID("AssemblyId",assemblyId);tag.putBoolean("Installed",installed);}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries){return saveWithoutMetadata(registries);}
    @Override public Packet<ClientGamePacketListener> getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
}
