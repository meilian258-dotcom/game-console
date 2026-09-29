package cn.piq.fcarcade.world;

import cn.piq.fcarcade.registry.ModBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Same cabinet settings, authorization and network tags as existing cabinets. */
public final class PortraitCabinetBlockEntity extends LegacyFcArcadeBlockEntity {
    private boolean fresh=true;
    public PortraitCabinetBlockEntity(BlockPos p,BlockState s){super(ModBlockEntities.PORTRAIT_CABINET.get(),p,s);}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);fresh=false;}
    @Override public void onLoad(){
        super.onLoad();
        if(fresh&&level!=null&&!level.isClientSide){
            fresh=false;
            var arcade=net.minecraft.resources.ResourceLocation.parse("piq_native_arcade:mame");
            if(cn.piq.fcarcade.cabinet.CabinetBackends.find(arcade)!=null)setCabinetBackend(arcade);
        }
    }
}
