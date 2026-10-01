// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.*;
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

/** Server-owned physical cartridge, controller loan and independent power generation. */
public final class MdConsole extends ExternalHomeConsoleBlockEntity {
    private ItemStack cartridge=ItemStack.EMPTY;
    private UUID borrower,loan,powerToken,powerSession,powerHost;
    private boolean running;
    private int ticks;
    public MdConsole(BlockPos p,BlockState s){super(MdMod.ENTITY.get(),p,s,MdMod.SYSTEM);}
    public boolean hasInsertedCartridge(){return !cartridge.isEmpty();}
    public ItemStack cartridge(){return cartridge;}
    public String cartridgeTitle(){return cartridge.isEmpty()?"未插卡":ContentCardData.title(cartridge).isBlank()?"空白卡带":ContentCardData.title(cartridge);}
    public UUID borrower(){return borrower;}
    public UUID loan(){return loan;}
    public UUID powerSession(){return powerSession;}
    public UUID powerHost(){return powerHost;}
    public boolean running(){return running;}
    public boolean powerOn(ServerPlayer p,HomeSystems.Connection connection){
        if(running)return false;
        var entry=ContentCardData.read(cartridge,MdMod.SYSTEM);
        if(entry==null){say(p,"这是空白 MD 卡带，请先右键老式电脑写入游戏。");return false;}
        if(borrower!=null&&!p.getUUID().equals(borrower)){say(p,"当前 MD 私人模式请由手柄借用者开机；尚未支持多人旁观。");return false;}
        var expectedCard=cartridge;var playerConnection=p.connection.getConnection();
        var generation=UUID.randomUUID();powerSession=generation;powerHost=p.getUUID();running=true;sync();
        powerToken=ContentCards.play(p,MdMod.SYSTEM,worldPosition,entry,()->running&&!isRemoved()&&cartridge==expectedCard
                &&generation.equals(powerSession)&&p.getUUID().equals(powerHost)&&p.connection.getConnection()==playerConnection&&p.serverLevel()==level
                &&level.hasChunkAt(worldPosition)&&level.getBlockEntity(worldPosition)==this&&HomeSystems.isCurrent(connection)&&connection.television().powered()
                &&p.serverLevel().mayInteract(p,worldPosition)&&p.serverLevel().mayInteract(p,connection.television().getBlockPos()),
                ready->{if(!ready&&generation.equals(powerSession)){running=false;powerToken=null;powerSession=null;powerHost=null;sync();} });
        if(powerToken==null){running=false;powerSession=null;powerHost=null;sync();say(p,"MD 启动服务繁忙，请稍后重试。");return false;}
        say(p,"正在启动卡带；拿起 1P 手柄操作，归还仅暂停输入，不关机。");return true;
    }
    public void powerOff(){
        UUID old=powerToken,host=powerHost;powerToken=null;powerSession=null;powerHost=null;running=false;
        if(level instanceof ServerLevel s&&old!=null&&host!=null){var p=s.getServer().getPlayerList().getPlayer(host);if(p!=null)ContentCards.stop(p,old);}
        sync();
    }
    public void reset(ServerPlayer p){
        if(!usable(p)||!running||powerHost==null||powerToken==null)return;
        if(!powerHost.equals(p.getUUID())){say(p,"当前私人游戏只能由开机玩家重置。");return;}
        if(ContentCards.reset(p,powerToken))HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.RESET);
    }
    private boolean usable(ServerPlayer p){
        return level==p.serverLevel()&&!isRemoved()&&p.isAlive()&&!p.isSpectator()&&!p.hasDisconnected()
                &&MdInteractionPolicy.inControllerRange(p.distanceToSqr(worldPosition.getCenter()))
                &&HomeHardware.mayUse(p,worldPosition)&&level.hasChunkAt(worldPosition)&&level.getBlockEntity(worldPosition)==this;
    }
    public void interact(ServerPlayer p,InteractionHand h){
        if(!usable(p))return;
        ItemStack held=p.getItemInHand(h);
        if(held.is(MdMod.CARTRIDGE.get())){
            if(running||visualPowered()){say(p,"请先关机再换卡。");return;}
            if(hasInsertedCartridge()){say(p,"先空手 Shift+右键取出已有卡带。");return;}
            cartridge=held.copyWithCount(1);held.shrink(1);notifyHardwareChanged();sync();
            HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CARTRIDGE_INSERT);return;
        }
        if(held.is(MdMod.CONTROLLER.get())){returnHeld(p,h);return;}
        if(!held.isEmpty())return;
        for(var hand:InteractionHand.values())if(p.getItemInHand(hand).is(MdMod.CONTROLLER.get())){returnHeld(p,hand);return;}
        if(p.isShiftKeyDown()){
            if(running||visualPowered()){say(p,"请先关机再取出卡带。");return;}
            if(!cartridge.isEmpty()){
                ItemStack returned=cartridge;cartridge=ItemStack.EMPTY;p.setItemInHand(h,returned);notifyHardwareChanged();sync();
                HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CARTRIDGE_EJECT);
            }return;
        }
        say(p,"瞄准电源、重置或 1P 手柄操作；卡带 Shift+右键取出。");
    }
    public void dock(ServerPlayer p,int port){
        if(!usable(p))return;
        if(port!=0){say(p,"MD 的 2P 尚未接通，本版只开放 1P；不会借出无效手柄。");return;}
        for(var hand:InteractionHand.values())if(p.getItemInHand(hand).is(MdMod.CONTROLLER.get())){returnHeld(p,hand);return;}
        if(loan!=null){say(p,p.getUUID().equals(borrower)?"请从物品栏拿起已借出的 1P 手柄。":"1P 手柄已被其他玩家借走。");return;}
        if(running&&!p.getUUID().equals(powerHost)){say(p,"当前 MD 为私人单人游戏，请等开机玩家关机。");return;}
        int empty=MdInteractionPolicy.emptyHand(p.getMainHandItem().isEmpty(),p.getOffhandItem().isEmpty());
        if(empty<0||!p.containerMenu.getCarried().isEmpty()){say(p,"请放下光标中的物品并腾出一只手。");return;}
        borrower=p.getUUID();loan=UUID.randomUUID();
        CompoundTag data=new CompoundTag();data.putUUID("MdLoan",loan);data.putUUID("MdConsole",hardwareId());
        data.putString("MdDimension",p.level().dimension().location().toString());data.putLong("MdPos",worldPosition.asLong());
        ItemStack pad=new ItemStack(MdMod.CONTROLLER.get());pad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        p.setItemInHand(empty==0?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND,pad);
        sync();HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CONTROLLER_TAKE);
    }
    private void returnHeld(ServerPlayer p,InteractionHand hand){
        var id=MdController.loan(p.getItemInHand(hand));
        if(id!=null&&id.equals(loan)&&p.getUUID().equals(borrower)){
            clearLoan();HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CONTROLLER_RETURN);
        }else say(p,"此手柄不属于这台主机，未改动；请归还原主机。");
    }
    /** Only release this physical loan. Power and native save lifecycle are separate. */
    public void clearLoan(){
        UUID old=loan,owner=borrower;loan=null;borrower=null;
        if(old!=null&&owner!=null&&level instanceof ServerLevel s){
            var p=s.getServer().getPlayerList().getPlayer(owner);
            if(p!=null){for(int i=0;i<p.getInventory().getContainerSize();i++){
                var item=p.getInventory().getItem(i);if(old.equals(MdController.loan(item)))item.setCount(0);
            }if(old.equals(MdController.loan(p.containerMenu.getCarried())))p.containerMenu.setCarried(ItemStack.EMPTY);p.inventoryMenu.broadcastChanges();}
        }sync();
    }
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
        if(loan==null||++ticks%10!=0)return;
        var p=((ServerLevel)level).getServer().getPlayerList().getPlayer(borrower);int count=0;
        if(p!=null&&usable(p)){
            for(int i=0;i<p.getInventory().getContainerSize();i++){var item=p.getInventory().getItem(i);if(loan.equals(MdController.loan(item)))count+=item.getCount();}
            if(loan.equals(MdController.loan(p.containerMenu.getCarried())))count+=p.containerMenu.getCarried().getCount();
        }
        if(count!=1)clearLoan();
    }
    public void dropCartridge(){if(!cartridge.isEmpty()){Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY()+.3,worldPosition.getZ()+.5,cartridge);cartridge=ItemStack.EMPTY;}}
    public void onLoad(){super.onLoad();if(level instanceof ServerLevel){borrower=null;loan=null;running=false;powerToken=null;powerSession=null;powerHost=null;sync();}}
    protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putBoolean("MdRunning",running);if(!cartridge.isEmpty())t.put("MdCartridge",cartridge.save(r));
        if(loan!=null&&borrower!=null){t.putUUID("MdLoan",loan);t.putUUID("MdBorrower",borrower);}
        if(powerSession!=null&&powerHost!=null){t.putUUID("MdPowerSession",powerSession);t.putUUID("MdPowerHost",powerHost);}
    }
    protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);running=t.getBoolean("MdRunning");cartridge=t.contains("MdCartridge")?ItemStack.parseOptional(r,t.getCompound("MdCartridge")):ItemStack.EMPTY;
        if(!cartridge.isEmpty()&&(!cartridge.is(MdMod.CARTRIDGE.get())||cartridge.getCount()!=1))cartridge=ItemStack.EMPTY;
        loan=t.hasUUID("MdLoan")?t.getUUID("MdLoan"):null;borrower=t.hasUUID("MdBorrower")?t.getUUID("MdBorrower"):null;
        powerSession=t.hasUUID("MdPowerSession")?t.getUUID("MdPowerSession"):null;powerHost=t.hasUUID("MdPowerHost")?t.getUUID("MdPowerHost"):null;
    }
    private static void say(ServerPlayer p,String s){p.displayClientMessage(net.minecraft.network.chat.Component.literal(s),true);}
}
