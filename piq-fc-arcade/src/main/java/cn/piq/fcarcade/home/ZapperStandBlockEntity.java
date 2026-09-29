package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlockEntities;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ZapperStandBlockEntity extends BlockEntity {
    private UUID identity=UUID.randomUUID();
    final ZapperDock<ItemStack> dock=new ZapperDock<>();
    private ZapperStandLinks.Link link;
    private boolean removedOnce;
    public ZapperStandBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.ZAPPER_STAND.get(),pos,state);}
    public UUID identity(){return identity;}
    public boolean occupied(){return dock.stored()!=null;}
    public ZapperDock.Loan loan(){return dock.loan();}
    public ZapperStandLinks.Link link(){return link;}
    public ZapperStandLinks.End endpoint(){return new ZapperStandLinks.End(level.dimension().location().toString(),worldPosition.getX(),worldPosition.getY(),worldPosition.getZ(),identity);}
    void link(ZapperStandLinks.Link value){link=value;changed();}
    boolean beginRemoval(){if(removedOnce)return false;removedOnce=true;return true;}
    void changed(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);}
    @Override public void onLoad(){super.onLoad();if(level instanceof ServerLevel){setChanged();ZapperStandService.loaded(this);}}
    @Override public void onChunkUnloaded(){if(level instanceof ServerLevel)ZapperStandService.unloaded(this);super.onChunkUnloaded();}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider registries){super.saveAdditional(t,registries);t.putUUID("StandId",identity);
        if(dock.stored()!=null)t.put("Gun",dock.stored().save(registries));
        if(dock.loan()!=null){t.putUUID("Loan",dock.loan().id());t.putUUID("Borrower",dock.loan().player());}
        if(link!=null)t.put("Link",ZapperStandService.Data.writeLink(link));}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider registries){super.loadAdditional(t,registries);
        if(t.hasUUID("StandId")&&!t.getUUID("StandId").equals(new UUID(0,0)))identity=t.getUUID("StandId");
        ItemStack item=t.contains("Gun",Tag.TAG_COMPOUND)?ItemStack.parseOptional(registries,t.getCompound("Gun")):ItemStack.EMPTY;
        if(item.getCount()!=1||!(item.getItem() instanceof HomeZapperItem))item=ItemStack.EMPTY;
        // A malformed double ownership state never issues a replacement gun.
        dock.restore(item.isEmpty()?null:item,item.isEmpty()&&t.hasUUID("Loan")&&t.hasUUID("Borrower")?new ZapperDock.Loan(t.getUUID("Loan"),t.getUUID("Borrower")):null);
        link=null;if(t.contains("Link",Tag.TAG_COMPOUND))try{link=ZapperStandService.Data.readLink(t.getCompound("Link"));}catch(IllegalArgumentException ignored){}
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider p){return saveWithoutMetadata(p);}
    @Override public Packet<ClientGamePacketListener> getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
}
