package cn.piq.computer.world;

import cn.piq.computer.ComputerRegistry;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class PeripheralEntity extends BlockEntity {
    public UUID id=UUID.randomUUID(),computerId;
    public BlockPos computer;
    public PeripheralEntity(BlockPos p,BlockState s){super(ComputerRegistry.PERIPHERAL.get(),p,s);}
    public boolean keyboard(){return ((PeripheralBlock)getBlockState().getBlock()).keyboard;}
    public boolean combined(){return ((PeripheralBlock)getBlockState().getBlock()).combined;}
    public boolean supports(boolean key){return combined()||keyboard()==key;}
    public ComputerEntity connected(){if(level==null||computer==null||computerId==null||!level.hasChunkAt(computer))return null;var pc=ComputerBlock.find(level,computer);return pc!=null&&computerId.equals(pc.hardwareId())&&pc.peripheral(keyboard())==this?pc:null;}
    public void sync(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);}
    public void unlink(){
        var pc=connected();computer=null;computerId=null;sync();
        if(pc!=null){if(supports(true)){pc.keyboard=null;pc.keyboardId=null;}if(supports(false)){pc.mouse=null;pc.mouseId=null;}pc.release();pc.sync();}
    }
    public void connect(ComputerEntity pc){
        unlink();computer=pc.getBlockPos();computerId=pc.hardwareId();
        if(supports(true)){pc.keyboard=worldPosition;pc.keyboardId=id;}if(supports(false)){pc.mouse=worldPosition;pc.mouseId=id;}pc.release();sync();pc.sync();
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);t.putUUID("Id",id);if(computer!=null&&computerId!=null){t.putLong("Computer",computer.asLong());t.putUUID("ComputerId",computerId);}}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);if(t.hasUUID("Id"))id=t.getUUID("Id");computerId=t.hasUUID("ComputerId")?t.getUUID("ComputerId"):null;computer=computerId==null?null:BlockPos.of(t.getLong("Computer"));}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){return saveWithoutMetadata(r);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    @Override public void onChunkUnloaded(){var pc=connected();if(pc!=null)pc.release();super.onChunkUnloaded();}
}
