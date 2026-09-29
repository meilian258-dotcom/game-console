// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** One server-owned physical loan, ephemeral across world reload. No ROM paths or native code. */
public final class MdConsole extends ExternalHomeConsoleBlockEntity {
    private ItemStack cartridge=ItemStack.EMPTY;
    private UUID borrower,loan;
    private int ticks;
    public MdConsole(BlockPos p,BlockState s){super(MdMod.ENTITY.get(),p,s,MdMod.SYSTEM);}
    public boolean hasInsertedCartridge(){return !cartridge.isEmpty();}
    public UUID borrower(){return borrower;}
    public UUID loan(){return loan;}
    public void interact(ServerPlayer p,InteractionHand h){
        if(p.distanceToSqr(worldPosition.getCenter())>36||p.isSpectator()||!p.isAlive())return;
        ItemStack held=p.getItemInHand(h);
        if(held.is(MdMod.CARTRIDGE.get())){
            if(hasInsertedCartridge()||loan!=null){say(p,"请先归还手柄并取出已有卡带。");return;}
            cartridge=held.copyWithCount(1);held.shrink(1);sync();return;
        }
        if(held.is(MdMod.CONTROLLER.get())){
            UUID id=MdController.loan(held);
            if(id!=null&&id.equals(loan)&&p.getUUID().equals(borrower)){held.shrink(1);clearLoan();}
            else {say(p,"此手柄不属于这台主机或借用已失效；物品未改动。");}
            return;
        }
        if(!held.isEmpty())return;
        if(p.isShiftKeyDown()){
            if(loan!=null){say(p,"先归还手柄再取出卡带。");return;}
            if(!cartridge.isEmpty()){ItemStack returned=cartridge;cartridge=ItemStack.EMPTY;if(!p.getInventory().add(returned))p.drop(returned,false);sync();}
            return;
        }
        if(!hasInsertedCartridge()){MdMod.hint(p);return;}
        if(loan!=null){say(p,"1P手柄已借出；初版私人局只接一个玩家。");return;}
        if(p.getInventory().getFreeSlot()<0){say(p,"背包需要一个空格借出手柄。");return;}
        borrower=p.getUUID();loan=UUID.randomUUID();
        CompoundTag data=new CompoundTag();data.putUUID("MdLoan",loan);data.putUUID("MdConsole",hardwareId());
        data.putString("MdDimension",p.level().dimension().location().toString());data.putLong("MdPos",worldPosition.asLong());
        ItemStack pad=new ItemStack(MdMod.CONTROLLER.get());pad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        if(!p.getInventory().add(pad)){borrower=null;loan=null;return;}
        sync();MdMod.hint(p);
    }
    public void clearLoan(){borrower=null;loan=null;sync();}
    private void sync(){
        if(level instanceof ServerLevel s&&!isRemoved()&&s.hasChunkAt(worldPosition)&&s.getBlockEntity(worldPosition)==this){
            BlockState next=getBlockState().setValue(MdBlock.INSERTED,!cartridge.isEmpty()).setValue(MdBlock.BORROWED,loan!=null);
            if(!next.equals(getBlockState()))s.setBlock(worldPosition,next,3);
            setChanged();s.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
        }
    }
    public void tick(){
        maintenanceTick();
        if(loan==null||++ticks%20!=0)return;
        var p=((ServerLevel)level).getServer().getPlayerList().getPlayer(borrower);int count=0;
        if(p!=null&&p.serverLevel()==level&&!p.hasDisconnected()&&p.isAlive()&&!p.isSpectator()&&p.distanceToSqr(worldPosition.getCenter())<=36)
            for(int i=0;i<p.getInventory().getContainerSize();i++){var item=p.getInventory().getItem(i);if(loan.equals(MdController.loan(item)))count+=item.getCount();}
        if(count!=1)clearLoan();
    }
    public void dropCartridge(){if(!cartridge.isEmpty()){Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY()+.3,worldPosition.getZ()+.5,cartridge);cartridge=ItemStack.EMPTY;}}
    public void onLoad(){super.onLoad();if(level instanceof ServerLevel){borrower=null;loan=null;sync();}}
    protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);if(!cartridge.isEmpty())t.put("MdCartridge",cartridge.save(r));if(loan!=null&&borrower!=null){t.putUUID("MdLoan",loan);t.putUUID("MdBorrower",borrower);}}
    protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);cartridge=t.contains("MdCartridge")?ItemStack.parseOptional(r,t.getCompound("MdCartridge")):ItemStack.EMPTY;if(!cartridge.isEmpty()&&(!cartridge.is(MdMod.CARTRIDGE.get())||cartridge.getCount()!=1))cartridge=ItemStack.EMPTY;loan=t.hasUUID("MdLoan")?t.getUUID("MdLoan"):null;borrower=t.hasUUID("MdBorrower")?t.getUUID("MdBorrower"):null;}
    private static void say(ServerPlayer p,String s){p.displayClientMessage(net.minecraft.network.chat.Component.literal(s),false);}
}
