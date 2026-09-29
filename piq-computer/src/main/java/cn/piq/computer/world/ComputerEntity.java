package cn.piq.computer.world;

import cn.piq.computer.*;
import cn.piq.computer.net.ComputerNetwork;
import cn.piq.fcarcade.home.*;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class ComputerEntity extends ExternalHomeConsoleBlockEntity {
    public static final ResourceLocation SYSTEM=ResourceLocation.fromNamespaceAndPath(ComputerMod.ID,"computer");
    public int installed;
    public boolean panelOpen,powered,removing,noCaseDrop;
    public BlockPos keyboard,mouse;
    public UUID keyboardId,mouseId,operator;
    public int pointerX=320,pointerY=240,buttons,lastKey;
    public String typed="";
    public final InputLease lease=new InputLease();
    private int ticks;
    private boolean dirtyInput;
    private long lastPowerTick=-100;
    public ComputerEntity(BlockPos p,BlockState s){super(ComputerRegistry.COMPUTER.get(),p,s,SYSTEM);}
    @Override public boolean isHardwareComplete(){return level!=null&&!isRemoved()&&level.getBlockEntity(worldPosition)==this&&getBlockState().getValue(ComputerBlock.HALF)==DoubleBlockHalf.LOWER;}
    public void sync(){setChanged();if(level!=null&&!level.isClientSide)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);}
    public static void say(ServerPlayer p,String text){p.displayClientMessage(Component.literal(text),true);}
    private void sound(float pitch){level.playSound(null,worldPosition,SoundEvents.UI_BUTTON_CLICK.value(),SoundSource.BLOCKS,.35f,pitch);}
    public void install(ServerPlayer p,ItemStack stack,Assembly.Part requested){
        if(powered){say(p,"先关机再安装零件");return;}
        if(!panelOpen){say(p,"空手 Shift + 右键打开侧板");return;}
        var part=requested==Assembly.Part.RAM&&Assembly.has(installed,requested)?Assembly.Part.RAM_2:requested;
        if(!Assembly.installable(installed,part)){say(p,Assembly.has(installed,part)?"这个插槽已有零件":"请先安装主板和对应处理器");return;}
        if(!stack.is(ComputerRegistry.item(part))||stack.isEmpty())return;
        installed|=part.bit();if(!p.isCreative())stack.shrink(1);sound(1.2f);sync();
    }
    public void removePart(ServerPlayer p,int ordinal){
        if(ordinal<0||ordinal>=Assembly.Part.values().length||powered||!panelOpen)return;
        var part=Assembly.Part.values()[ordinal];
        if(!Assembly.removable(installed,part)){say(p,"请先取下安装在它上面的零件");return;}
        installed&=~part.bit();ItemStack returned=new ItemStack(ComputerRegistry.item(part));if(!p.addItem(returned))p.drop(returned,false);sound(.9f);sync();
    }
    public void togglePanel(ServerPlayer p){panelOpen=!panelOpen;sound(panelOpen?1.1f:.9f);sync();say(p,panelOpen?(powered?"侧板已打开；拆装零件前请关机":"手持零件右键安装；空手右键查看 / 拆卸"):"侧板已合上");}
    public void togglePower(ServerPlayer p){
        if(level.getGameTime()-lastPowerTick<6)return;
        lastPowerTick=level.getGameTime();
        if(powered){stop();sound(.8f);return;}
        if(!isHardwareComplete()||!Assembly.ready(installed)){say(p,"缺少："+Assembly.missing(installed).stream().map(a->a.label).collect(Collectors.joining("、")));return;}
        var connection=HomeSystems.connection(p.serverLevel(),worldPosition);
        if(connection.isEmpty()){say(p,"先用视频线连接主机和电视");return;}
        var tv=connection.get().television();
        if(!ComputerAccess.allowed(p,tv.getBlockPos())||!HomeSystems.isCurrent(connection.get()))return;
        if(!tv.powered()){say(p,"先打开电视");return;}
        powered=true;typed="";lastKey=buttons=0;pointerX=320;pointerY=240;sync();sound(1.15f);
        HomeApplianceService.refresh(p.serverLevel(),tv.getBlockPos());
        say(p,"右键已连接的键盘或鼠标进入操作，Esc 退出");
    }
    public void openAssembly(ServerPlayer p){ComputerNetwork.open(p,this,false,new UUID(0,0));}
    public PeripheralEntity peripheral(boolean key){
        var pos=key?keyboard:mouse;var id=key?keyboardId:mouseId;
        if(level==null||pos==null||id==null||!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof PeripheralEntity e)||!id.equals(e.id)||!hardwareId().equals(e.computerId)||!worldPosition.equals(e.computer)||!e.supports(key))return null;
        return e;
    }
    public boolean inputReady(){return powered&&isHardwareComplete()&&peripheral(true)!=null&&peripheral(false)!=null;}
    public void control(ServerPlayer p,PeripheralEntity from){
        if(!inputReady()){say(p,!powered?"先打开电脑":"请连接键盘和鼠标");return;}
        if(from!=peripheral(true)&&from!=peripheral(false))return;
        if(!ComputerAccess.allowed(p,worldPosition)||!ComputerAccess.allowed(p,keyboard)||!ComputerAccess.allowed(p,mouse)||televisionPos()==null||!ComputerAccess.allowed(p,televisionPos())||!inputReady())return;
        if(!ComputerStreamServer.mayControl(this,p)){say(p,"共享电脑：请运行者先允许交接键鼠");return;}
        var token=lease.acquire(p.getUUID(),level.getGameTime());
        if(token==null){say(p,"这台电脑正在使用中");return;}
        operator=p.getUUID();buttons=lastKey=0;sync();ComputerStreamServer.controller(this);ComputerNetwork.open(p,this,true,token);
    }
    public boolean currentUser(ServerPlayer p){return inputReady()&&ComputerAccess.valid(p,worldPosition,8)&&ComputerAccess.near(p,keyboard)&&ComputerAccess.near(p,mouse)&&televisionPos()!=null&&ComputerAccess.valid(p,televisionPos(),8)&&HomeSystems.connection(p.serverLevel(),worldPosition).filter(c->c.television().powered()).isPresent();}
    public void release(){lease.clear();operator=null;buttons=lastKey=0;sync();ComputerStreamServer.controller(this);}
    public void stop(){ComputerStreamServer.end(this);if(powered||operator!=null){powered=false;release();}}
    public void input(int kind,int value,int x,int y,int mask){
        pointerX=Math.max(0,Math.min(639,x));pointerY=Math.max(0,Math.min(479,y));buttons=mask&7;
        if(kind==1){lastKey=value;if(value==259&&!typed.isEmpty())typed=typed.substring(0,typed.offsetByCodePoints(typed.length(),-1));if(value==257)typed="";}
        if(kind==2&&lastKey==value)lastKey=0;
        if(kind==3&&Character.isValidCodePoint(value)&&!Character.isISOControl(value)){
            typed+=new String(Character.toChars(value));if(typed.codePointCount(0,typed.length())>48)typed=typed.substring(typed.offsetByCodePoints(0,1));
        }
        dirtyInput=true;
    }
    public void serverTick(){
        maintenanceTick();ticks++;
        var old=level.getBlockState(worldPosition.above());
        if(old.is(getBlockState().getBlock())&&old.getValue(ComputerBlock.HALF)==DoubleBlockHalf.UPPER&&old.getValue(ComputerBlock.FACING)==getBlockState().getValue(ComputerBlock.FACING)){
            removing=true;try{level.removeBlock(worldPosition.above(),false);}finally{removing=false;}
        }
        if(!isHardwareComplete()){level.destroyBlock(worldPosition,false);return;}
        if(powered&&HomeSystems.connection((ServerLevel)level,worldPosition).filter(c->c.television().powered()).isEmpty())stop();
        if(operator!=null){
            var p=((ServerLevel)level).getServer().getPlayerList().getPlayer(operator);
            if(p==null||p.level()!=level||!lease.active(level.getGameTime())||!currentUser(p))release();
            else if(ticks%20==0&&(!ComputerAccess.allowed(p,worldPosition)||!ComputerAccess.allowed(p,keyboard)||!ComputerAccess.allowed(p,mouse)||televisionPos()==null||!ComputerAccess.allowed(p,televisionPos())))release();
        }
        if(dirtyInput&&ticks%2==0){dirtyInput=false;sync();}
    }
    public void dropContents(){
        stop();
        for(boolean key:new boolean[]{true,false}){var e=peripheral(key);if(e!=null)e.unlink();}
        for(var part:Assembly.Part.values())if(Assembly.has(installed,part))Block.popResource(level,worldPosition,new ItemStack(ComputerRegistry.item(part)));
        installed=0;if(!noCaseDrop)Block.popResource(level,worldPosition,new ItemStack(getBlockState().getBlock()));
    }
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide){powered=false;lease.clear();operator=null;buttons=lastKey=0;sync();}}
    @Override public void onChunkUnloaded(){stop();super.onChunkUnloaded();}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putInt("Parts",installed);t.putBoolean("PanelOpen",panelOpen);t.putBoolean("PcPower",powered);
        saveLink(t,"Keyboard",keyboard,keyboardId);saveLink(t,"Mouse",mouse,mouseId);
        if(operator!=null)t.putUUID("Operator",operator);t.putInt("X",pointerX);t.putInt("Y",pointerY);t.putInt("Buttons",buttons);t.putInt("Key",lastKey);t.putString("Typed",typed);
    }
    private static void saveLink(CompoundTag t,String key,BlockPos p,UUID id){if(p!=null&&id!=null){t.putLong(key+"Pos",p.asLong());t.putUUID(key+"Id",id);}}
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);installed=Assembly.clean(t.getInt("Parts"));panelOpen=t.getBoolean("PanelOpen");powered=t.getBoolean("PcPower");
        keyboardId=t.hasUUID("KeyboardId")?t.getUUID("KeyboardId"):null;keyboard=keyboardId!=null?BlockPos.of(t.getLong("KeyboardPos")):null;
        mouseId=t.hasUUID("MouseId")?t.getUUID("MouseId"):null;mouse=mouseId!=null?BlockPos.of(t.getLong("MousePos")):null;
        operator=t.hasUUID("Operator")?t.getUUID("Operator"):null;pointerX=Math.clamp(t.getInt("X"),0,639);pointerY=Math.clamp(t.getInt("Y"),0,479);buttons=t.getInt("Buttons")&7;lastKey=t.getInt("Key");typed=t.getString("Typed");if(typed.length()>96)typed=typed.substring(0,96);
    }
}
