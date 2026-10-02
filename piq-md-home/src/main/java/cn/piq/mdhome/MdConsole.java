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
    private final UUID[] borrowers=new UUID[2],loans=new UUID[2];
    private final net.minecraft.network.Connection[] loanConnections=new net.minecraft.network.Connection[2];
    private UUID powerToken,powerSession,powerHost;
    private boolean running,publicPlay;
    private int ticks;
    public MdConsole(BlockPos p,BlockState s){super(MdMod.ENTITY.get(),p,s,MdMod.SYSTEM);}
    public boolean hasInsertedCartridge(){return !cartridge.isEmpty();}
    public ItemStack cartridge(){return cartridge;}
    public String cartridgeTitle(){return cartridge.isEmpty()?"未插卡":ContentCardData.title(cartridge).isBlank()?"空白卡带":ContentCardData.title(cartridge);}
    public UUID borrower(){return borrower(0);}
    public UUID loan(){return loan(0);}
    public UUID borrower(int port){return port>=0&&port<2?borrowers[port]:null;}
    public UUID loan(int port){return port>=0&&port<2?loans[port]:null;}
    public boolean publicPlay(){return publicPlay;}
    public UUID powerSession(){return powerSession;}
    public UUID powerHost(){return powerHost;}
    public boolean running(){return running;}
    public boolean powerOn(ServerPlayer p,HomeSystems.Connection connection){
        if(running)return false;
        var entry=ContentCardData.read(cartridge,MdMod.SYSTEM);
        if(entry==null){say(p,"这是空白 MD 卡带，请先右键老式电脑写入游戏。");return false;}
        if(!MdPublicServer.privatePlay(p))return MdPublicServer.start(p,this,connection,entry);
        if(ContentCardData.saveMode(cartridge)==1){say(p,"卡带归属存档请用公开游玩；私人模式不会将其写成个人本机档。");return false;}
        if(borrower()!=null&&!p.getUUID().equals(borrower())){say(p,"私人模式请由 1P 手柄借用者开机。");return false;}
        clearLoan(1);publicPlay=false;
        var expectedCard=cartridge;var playerConnection=p.connection.getConnection();
        var generation=UUID.randomUUID();powerSession=generation;powerHost=p.getUUID();running=true;sync();
        powerToken=ContentCards.play(p,MdMod.SYSTEM,worldPosition,entry,()->running&&!isRemoved()&&cartridge==expectedCard
                &&generation.equals(powerSession)&&p.getUUID().equals(powerHost)&&p.connection.getConnection()==playerConnection&&p.serverLevel()==level
                &&level.hasChunkAt(worldPosition)&&level.getBlockEntity(worldPosition)==this&&HomeSystems.isCurrent(connection)&&connection.television().powered()
                &&p.serverLevel().mayInteract(p,worldPosition)&&p.serverLevel().mayInteract(p,connection.television().getBlockPos()),
                ready->{if(!ready&&generation.equals(powerSession)){if(powerToken!=null&&p.connection.getConnection()==playerConnection&&!p.hasDisconnected())MdPublicNetwork.send(p,new MdPublicNetwork.PrivateStart(powerToken,false));running=false;powerToken=null;powerSession=null;powerHost=null;sync();} });
        if(powerToken==null){running=false;powerSession=null;powerHost=null;sync();say(p,"MD 启动服务繁忙，请稍后重试。");return false;}
        MdPublicNetwork.send(p,new MdPublicNetwork.PrivateStart(powerToken,true));
        say(p,"正在启动卡带；拿起 1P 手柄操作，归还仅暂停输入，不关机。");return true;
    }
    public void powerOff(){
        if(level instanceof ServerLevel s)cn.piq.mdhome.save.MdPublicSaves.cancel(s.getServer(),hardwareId());
        if(publicPlay){MdPublicServer.stop(this,"实体主机关机，正在保存");return;}
        UUID old=powerToken,host=powerHost;powerToken=null;powerSession=null;powerHost=null;running=false;
        if(level instanceof ServerLevel s&&old!=null&&host!=null){var p=s.getServer().getPlayerList().getPlayer(host);if(p!=null){ContentCards.stop(p,old);MdPublicNetwork.send(p,new MdPublicNetwork.PrivateStart(old,false));}}
        sync();
    }
    public void reset(ServerPlayer p){
        if(publicPlay){MdPublicServer.reset(p,this);return;}
        if(!usable(p)||!running||powerHost==null||powerToken==null)return;
        if(!powerHost.equals(p.getUUID())){say(p,"当前私人游戏只能由开机玩家重置。");return;}
        if(ContentCards.reset(p,powerToken))HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.RESET);
    }
    public boolean usable(ServerPlayer p){
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
        say(p,"瞄准电源、重置或 1P / 2P 手柄操作；卡带 Shift+右键取出。");
    }
    public void dock(ServerPlayer p,int port){
        if(!usable(p))return;
        if(port<0||port>1)return;
        for(var hand:InteractionHand.values())if(p.getItemInHand(hand).is(MdMod.CONTROLLER.get())){returnHeld(p,hand);return;}
        if(loan(port)!=null){say(p,p.getUUID().equals(borrower(port))?"请从物品栏拿起已借出的手柄。":(port+1)+"P 手柄已被其他玩家借走。");return;}
        if(p.getUUID().equals(borrower(1-port))){say(p,"每位玩家只能借用一个端口，请先归还另一只手柄。");return;}
        if(running&&(!publicPlay&&(port!=0||!p.getUUID().equals(powerHost))||publicPlay&&!MdPublicServer.allowedPort(this,port))){say(p,"当前开局未开放这个手柄端口；请关机后由主持重新选择。");return;}
        int empty=MdInteractionPolicy.emptyHand(p.getMainHandItem().isEmpty(),p.getOffhandItem().isEmpty());
        if(empty<0||!p.containerMenu.getCarried().isEmpty()){say(p,"请放下光标中的物品并腾出一只手。");return;}
        borrowers[port]=p.getUUID();loans[port]=UUID.randomUUID();loanConnections[port]=p.connection.getConnection();
        CompoundTag data=new CompoundTag();data.putUUID("MdLoan",loans[port]);data.putUUID("MdConsole",hardwareId());data.putInt("MdPort",port);
        data.putString("MdDimension",p.level().dimension().location().toString());data.putLong("MdPos",worldPosition.asLong());
        ItemStack pad=new ItemStack(MdMod.CONTROLLER.get());pad.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        p.setItemInHand(empty==0?InteractionHand.MAIN_HAND:InteractionHand.OFF_HAND,pad);
        sync();MdPublicServer.loanChanged(this);HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CONTROLLER_TAKE);
    }
    private void returnHeld(ServerPlayer p,InteractionHand hand){
        var id=MdController.loan(p.getItemInHand(hand));
        int port=MdController.port(p.getItemInHand(hand));
        if(id!=null&&id.equals(loan(port))&&p.getUUID().equals(borrower(port))){
            clearLoan(port);HomeInteractionSounds.play(p.serverLevel(),worldPosition,HomeInteractionSounds.Action.CONTROLLER_RETURN);
        }else say(p,"此手柄不属于这台主机，未改动；请归还原主机。");
    }
    /** Only release this physical loan. Power and native save lifecycle are separate. */
    public void clearLoan(){
        clearLoan(0);clearLoan(1);
    }
    public void clearLoan(int port){
        if(port<0||port>1)return;
        UUID old=loans[port],owner=borrowers[port];loans[port]=null;borrowers[port]=null;loanConnections[port]=null;
        if(old!=null&&owner!=null&&level instanceof ServerLevel s){
            var p=s.getServer().getPlayerList().getPlayer(owner);
            if(p!=null){var items=personalItems(p);for(var slot:p.containerMenu.slots)items.add(slot.getItem());items.add(p.containerMenu.getCarried());
                HomeControllerInventory.recycle(items,item->old.equals(MdController.loan(item)),ItemStack::getCount,item->item.setCount(0));
                for(var slot:p.containerMenu.slots)slot.setChanged();p.getInventory().setChanged();p.inventoryMenu.broadcastChanges();if(p.containerMenu!=p.inventoryMenu)p.containerMenu.broadcastChanges();}
        }sync();MdPublicServer.loanChanged(this);
    }
    public boolean authorized(ServerPlayer p,int port,UUID lease,boolean held){
        if(port<0||port>1||lease==null||!lease.equals(loan(port))||!p.getUUID().equals(borrower(port))||loanConnections[port]!=p.connection.getConnection()||!usable(p))return false;
        if(p.containerMenu!=p.inventoryMenu)for(var slot:p.containerMenu.slots)if(slot.container!=p.getInventory()&&lease.equals(MdController.loan(slot.getItem())))return false;
        var found=HomeControllerInventory.locate(lease,personalItems(p),MdController::loan,ItemStack::getCount);
        return found.status()==HomeControllerInventory.Status.UNIQUE&&MdController.port(found.value())==port&&(!held||p.containerMenu==p.inventoryMenu&&p.containerMenu.getCarried().isEmpty()&&(lease.equals(MdController.loan(p.getMainHandItem()))||lease.equals(MdController.loan(p.getOffhandItem()))));
    }
    private static java.util.List<ItemStack> personalItems(ServerPlayer p){var items=new java.util.ArrayList<ItemStack>();for(int i=0;i<p.getInventory().getContainerSize();i++)items.add(p.getInventory().getItem(i));for(var slot:p.inventoryMenu.slots)items.add(slot.getItem());items.add(p.containerMenu.getCarried());items.add(p.inventoryMenu.getCarried());return items;}
    void publicPower(UUID session,UUID host){publicPlay=true;powerSession=session;powerHost=host;running=true;sync();}
    void publicStopped(){publicPlay=false;powerSession=null;powerHost=null;powerToken=null;running=false;sync();}
    private void sync(){
        if(level instanceof ServerLevel s&&!isRemoved()&&s.hasChunkAt(worldPosition)&&s.getBlockEntity(worldPosition)==this){
            BlockState next=getBlockState().setValue(MdBlock.INSERTED,!cartridge.isEmpty()).setValue(MdBlock.BORROWED,loan(0)!=null).setValue(MdBlock.BORROWED_TWO,loan(1)!=null);
            if(!next.equals(getBlockState()))s.setBlock(worldPosition,next,3);
            setChanged();s.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
            if(televisionPos()!=null)HomeApplianceService.refresh(s,televisionPos());
        }
    }
    public void tick(){
        maintenanceTick();
        if(++ticks%10!=0)return;
        for(int port=0;port<2;port++){if(loan(port)==null)continue;var p=((ServerLevel)level).getServer().getPlayerList().getPlayer(borrower(port));if(p==null||!authorized(p,port,loan(port),false))clearLoan(port);}
    }
    public void dropCartridge(){if(!cartridge.isEmpty()){Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY()+.3,worldPosition.getZ()+.5,cartridge);cartridge=ItemStack.EMPTY;}}
    public void onLoad(){super.onLoad();if(level instanceof ServerLevel){java.util.Arrays.fill(borrowers,null);java.util.Arrays.fill(loans,null);java.util.Arrays.fill(loanConnections,null);running=false;publicPlay=false;powerToken=null;powerSession=null;powerHost=null;useSupportedSynchronizationFallback(cn.piq.fcarcade.cabinet.CabinetSyncMode.MEDIA);sync();}}
    protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putBoolean("MdRunning",running);t.putBoolean("MdPublic",publicPlay);if(!cartridge.isEmpty())t.put("MdCartridge",cartridge.save(r));
        for(int port=0;port<2;port++)if(loan(port)!=null&&borrower(port)!=null){t.putUUID(port==0?"MdLoan":"MdLoan2",loan(port));t.putUUID(port==0?"MdBorrower":"MdBorrower2",borrower(port));}
        if(powerSession!=null&&powerHost!=null){t.putUUID("MdPowerSession",powerSession);t.putUUID("MdPowerHost",powerHost);}
    }
    protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);running=t.getBoolean("MdRunning");publicPlay=t.getBoolean("MdPublic");cartridge=t.contains("MdCartridge")?ItemStack.parseOptional(r,t.getCompound("MdCartridge")):ItemStack.EMPTY;
        if(!cartridge.isEmpty()&&(!cartridge.is(MdMod.CARTRIDGE.get())||cartridge.getCount()!=1))cartridge=ItemStack.EMPTY;
        for(int port=0;port<2;port++){String lk=port==0?"MdLoan":"MdLoan2",bk=port==0?"MdBorrower":"MdBorrower2";loans[port]=t.hasUUID(lk)?t.getUUID(lk):null;borrowers[port]=t.hasUUID(bk)?t.getUUID(bk):null;}
        powerSession=t.hasUUID("MdPowerSession")?t.getUUID("MdPowerSession"):null;powerHost=t.hasUUID("MdPowerHost")?t.getUUID("MdPowerHost"):null;
    }
    private static void say(ServerPlayer p,String s){p.displayClientMessage(net.minecraft.network.chat.Component.literal(s),true);}
}
