// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.ExternalHomeConsoleBlockEntity;
import cn.piq.fcarcade.home.HomeSystems;
import cn.piq.fcarcade.home.HomeApplianceService;
import cn.piq.fcarcade.home.content.*;
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
    private UUID powerToken;
    private boolean running;
    private int ticks;
    public MdConsole(BlockPos p,BlockState s){super(MdMod.ENTITY.get(),p,s,MdMod.SYSTEM);}
    public boolean hasInsertedCartridge(){return !cartridge.isEmpty();}
    public String cartridgeTitle(){return cartridge.isEmpty()?"未插卡":ContentCardData.title(cartridge).isBlank()?"空白卡带":ContentCardData.title(cartridge);}
    public UUID borrower(){return borrower;}
    public UUID loan(){return loan;}
    public boolean running(){return running;}
    public boolean powerOn(ServerPlayer p,HomeSystems.Connection connection){
        if(running)return false;
        var entry=ContentCardData.read(cartridge,MdMod.SYSTEM);
        if(entry==null){say(p,"这是空白 MD 卡带，请先右键老式电脑写入游戏。");return false;}
        if(loan==null){interact(p,InteractionHand.MAIN_HAND);}
        if(loan==null||!p.getUUID().equals(borrower)){say(p,"请先空手右键借取这台主机的 1P 手柄。");return false;}
        var expectedLoan=loan;var expectedCard=cartridge;var playerConnection=p.connection.getConnection();
        running=true;sync();
        powerToken=ContentCards.play(p,MdMod.SYSTEM,worldPosition,entry,()->running&&!isRemoved()&&cartridge==expectedCard&&expectedLoan.equals(loan)
                &&p.getUUID().equals(borrower)&&p.connection.getConnection()==playerConnection&&p.serverLevel()==level
                &&p.distanceToSqr(worldPosition.getCenter())<=36&&HomeSystems.isCurrent(connection)&&connection.television().powered()
                &&p.serverLevel().mayInteract(p,worldPosition)&&p.serverLevel().mayInteract(p,connection.television().getBlockPos()),
                ready->{if(!ready){running=false;powerToken=null;sync();} });
        if(powerToken==null){running=false;sync();say(p,"MD 启动服务繁忙，请稍后重试。");return false;}
        say(p,"正在校验卡带并启动 JNI；请手持 1P 手柄操作。首次从服务器读取游戏需要稍等。");return true;
    }
    public void powerOff(){
        UUID old=powerToken;powerToken=null;running=false;
        if(level instanceof ServerLevel s&&old!=null&&borrower!=null){var p=s.getServer().getPlayerList().getPlayer(borrower);if(p!=null)ContentCards.stop(p,old);}
        sync();
    }
    public void interact(ServerPlayer p,InteractionHand h){
        if(p.distanceToSqr(worldPosition.getCenter())>36||p.isSpectator()||!p.isAlive())return;
        ItemStack held=p.getItemInHand(h);
        if(held.is(MdMod.CARTRIDGE.get())){
            if(running){say(p,"请先关机再换卡。");return;}
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
    public void clearLoan(){powerOff();borrower=null;loan=null;sync();}
    private void sync(){
        if(level instanceof ServerLevel s&&!isRemoved()&&s.hasChunkAt(worldPosition)&&s.getBlockEntity(worldPosition)==this){
            BlockState next=getBlockState().setValue(MdBlock.INSERTED,!cartridge.isEmpty()).setValue(MdBlock.BORROWED,loan!=null);
            if(!next.equals(getBlockState()))s.setBlock(worldPosition,next,3);
            setChanged();s.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
            if(televisionPos()!=null)HomeApplianceService.refresh(s,televisionPos());
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
    public void onLoad(){super.onLoad();if(level instanceof ServerLevel){borrower=null;loan=null;running=false;powerToken=null;sync();}}
    protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){super.saveAdditional(t,r);t.putBoolean("MdRunning",running);if(!cartridge.isEmpty())t.put("MdCartridge",cartridge.save(r));if(loan!=null&&borrower!=null){t.putUUID("MdLoan",loan);t.putUUID("MdBorrower",borrower);}}
    protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){super.loadAdditional(t,r);running=t.getBoolean("MdRunning");cartridge=t.contains("MdCartridge")?ItemStack.parseOptional(r,t.getCompound("MdCartridge")):ItemStack.EMPTY;if(!cartridge.isEmpty()&&(!cartridge.is(MdMod.CARTRIDGE.get())||cartridge.getCount()!=1))cartridge=ItemStack.EMPTY;loan=t.hasUUID("MdLoan")?t.getUUID("MdLoan"):null;borrower=t.hasUUID("MdBorrower")?t.getUUID("MdBorrower"):null;}
    private static void say(ServerPlayer p,String s){p.displayClientMessage(net.minecraft.network.chat.Component.literal(s),false);}
}
