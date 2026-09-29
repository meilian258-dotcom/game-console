package cn.piq.fcarcade.home;

import cn.piq.sfchome.world.SfcHomeConsoleBlock;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.world.FamicomConsoleBlock;
import cn.piq.sfchome.world.SfcHomeConsoleBlockEntity;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** Actual production block method, actual MC ItemStack/Player API; no live world is created. */
public final class ActualSfcCableDispatchProbe {
    private static Unsafe unsafe;
    private static int checks;
    public static class TestServer extends DedicatedServer {
        private TestServer(){super(Thread.currentThread(),null,null,null,null,null,null,null);}
        @Override public Thread getRunningThread(){return Thread.currentThread();}
    }
    public static class MemoryData extends DimensionDataStorage {
        Map<String,SavedData> saved;
        private MemoryData(){super(new java.io.File("."),null,null);}
        @Override @SuppressWarnings("unchecked") public <T extends SavedData>T computeIfAbsent(SavedData.Factory<T> f,String name){return (T)saved.computeIfAbsent(name,k->f.constructor().get());}
    }
    public static class TestLevel extends ServerLevel {
        Map<BlockPos,BlockEntity> entities; Set<BlockPos> missing,denied; MemoryData data; TestServer server;
        private TestLevel(){super(null,null,null,null,Level.OVERWORLD,null,null,false,0,List.of(),false,null);}
        @Override public boolean hasChunkAt(BlockPos p){return !missing.contains(p);}
        @Override public BlockEntity getBlockEntity(BlockPos p){return entities.get(p);}
        @Override public BlockState getBlockState(BlockPos p){var be=entities.get(p);return be==null?Blocks.AIR.defaultBlockState():be.getBlockState();}
        @Override public boolean mayInteract(Player p,BlockPos at){return !denied.contains(at);}
        @Override public MinecraftServer getServer(){return server;}
        @Override public ResourceKey<Level> dimension(){return Level.OVERWORLD;}
        @Override public DimensionDataStorage getDataStorage(){return data;}
        @Override public void sendBlockUpdated(BlockPos p,BlockState a,BlockState b,int flags){}
        @Override public void blockEntityChanged(BlockPos p){}
    }
    public static final class WirePlayer extends ServerPlayer {
        TestLevel world; ItemStack main,off; String message; boolean shift;
        private WirePlayer(){super(null,null,new GameProfile(new UUID(0,2),"wire"),net.minecraft.server.level.ClientInformation.createDefault());}
        @Override public ServerLevel serverLevel(){return world;}
        @Override public Level level(){return world;}
        @Override public MinecraftServer getServer(){return world.server;}
        @Override public ItemStack getMainHandItem(){return main;}
        @Override public ItemStack getOffhandItem(){return off;}
        @Override public ItemStack getItemInHand(InteractionHand h){return h==InteractionHand.MAIN_HAND?main:off;}
        @Override public boolean isShiftKeyDown(){return shift;}
        @Override public double distanceToSqr(double x,double y,double z){return 0;}
        @Override public void displayClientMessage(Component c,boolean actionBar){message=c.getString();}
    }
    static void field(Object target,String name,Object value)throws Exception{
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static TestLevel level(boolean client)throws Exception{
        var l=(TestLevel)unsafe.allocateInstance(TestLevel.class);l.entities=new HashMap<>();l.missing=new HashSet<>();l.denied=new HashSet<>();l.server=(TestServer)unsafe.allocateInstance(TestServer.class);l.data=(MemoryData)unsafe.allocateInstance(MemoryData.class);l.data.saved=new HashMap<>();field(l,"isClientSide",client);return l;
    }
    static <T extends BlockEntity>T entity(Class<T> type,TestLevel level,BlockPos pos,BlockState state)throws Exception{
        T be=(T)unsafe.allocateInstance(type);field(be,"worldPosition",pos);field(be,"blockState",state);field(be,"level",level);if(be instanceof HomeEndpointBlockEntity)field(be,"hardwareId",UUID.randomUUID());level.entities.put(pos,be);return be;
    }
    static HomeEndpointBlockEntity console(TestLevel l,int style,BlockPos p)throws Exception{
        if(style==2){var e=entity(SfcHomeConsoleBlockEntity.class,l,p,new SfcHomeConsoleBlock(Block.Properties.of()).defaultBlockState());field(e,"systemId",SfcHomeConsoleBlockEntity.SYSTEM_ID);field(e,"cartridge",ItemStack.EMPTY);return e;}
        var b=style==0?new FamicomConsoleBlock(Block.Properties.of()):new SuborConsoleBlock(Block.Properties.of());
        var state=b.defaultBlockState();if(style==1)state=state.setValue(SuborConsoleBlock.WIDE,true);
        var e=entity(HomeConsoleBlockEntity.class,l,p,state);field(e,"cartridge",new HomeCartridgeSlot<ItemStack>());
        if(style==1){field(e,"suborStructureInstalled",true);var face=SuborFootprint.Facing.NORTH;SuborAssemblyData.get(l).ledger.restore(new SuborAssemblyLedger.Assembly(e.hardwareId(),p.getX(),p.getY(),p.getZ(),face,false,0));
            for(var c:SuborFootprint.cells(face))if(c.part()!=0){var part=entity(SuborPartBlockEntity.class,l,p.offset(c.x(),c.y(),c.z()),new SuborPartBlock(Block.Properties.of()).defaultBlockState().setValue(SuborPartBlock.PART,c.part()));field(part,"owner",e.hardwareId());field(part,"anchor",p);}}
        return e;
    }
    static HomeTvBlockEntity tv(TestLevel l,int style,BlockPos p)throws Exception{
        RetroTvBlock b=switch(style){case 2->new LcdTvBlock(Block.Properties.of());case 3->new WideLcdTvBlock(Block.Properties.of());case 4->new LargeLcdTvBlock(Block.Properties.of());case 5->new VintageTvBlock(Block.Properties.of());default->new RetroTvBlock(Block.Properties.of());};
        var state=b.defaultBlockState();if(style==1)state=state.setValue(RetroTvBlock.CENTERED,true);
        var e=entity(HomeTvBlockEntity.class,l,p,state);field(e,"structureInstalled",true);
        if(style==0||style==1){boolean centered=style==1;var face=HomeTvFootprint.Facing.NORTH;HomeTvAssemblyData.get(l).ledger.restore(new HomeTvAssemblyLedger.Assembly(e.hardwareId(),p.getX(),p.getY(),p.getZ(),face,false,0,centered));
            for(var c:HomeTvFootprint.cells(face,centered))if(c.part()!=0){var part=entity(HomeTvPartBlockEntity.class,l,p.offset(c.x(),c.y(),c.z()),new HomeTvPartBlock(Block.Properties.of()).defaultBlockState().setValue(HomeTvPartBlock.PART,c.part()).setValue(RetroTvBlock.CENTERED,centered));field(part,"owner",e.hardwareId());field(part,"anchor",p);}}
        if(style==3){var face=WideLcdTvFootprint.Facing.NORTH;WideLcdTvAssemblyData.get(l).ledger.restore(new WideLcdTvAssemblyLedger.Assembly(e.hardwareId(),p.getX(),p.getY(),p.getZ(),face,false,0,false));
            for(var c:WideLcdTvFootprint.cells(face,false))if(c.part()!=0){var part=entity(WideLcdTvPartBlockEntity.class,l,p.offset(c.x(),c.y(),c.z()),new WideLcdTvPartBlock(Block.Properties.of()).defaultBlockState().setValue(WideLcdTvPartBlock.PART,c.part()));field(part,"owner",e.hardwareId());field(part,"anchor",p);}}
        if(style==4){var face=LargeLcdTvFootprint.Facing.NORTH;LargeLcdTvAssemblyData.get(l).ledger.restore(new LargeLcdTvAssemblyLedger.Assembly(e.hardwareId(),p.getX(),p.getY(),p.getZ(),face,false,0,false));
            for(var c:LargeLcdTvFootprint.cells(face,false))if(c.part()!=0){var part=entity(LargeLcdTvPartBlockEntity.class,l,p.offset(c.x(),c.y(),c.z()),new LargeLcdTvPartBlock(Block.Properties.of()).defaultBlockState().setValue(LargeLcdTvPartBlock.PART,c.part()));field(part,"owner",e.hardwareId());field(part,"anchor",p);}}
        check(HomeTvStructure.complete(l,p),"actual TV structure fixture style="+style);return e;
    }
    static WirePlayer wirePlayer(TestLevel l)throws Exception{var p=(WirePlayer)unsafe.allocateInstance(WirePlayer.class);p.world=l;p.main=new ItemStack(Items.STICK,4);p.off=ItemStack.EMPTY;return p;}
    static InteractionResult click(AvCableItem cable,WirePlayer p,BlockPos at){return cable.useOn(new UseOnContext(p,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(at),Direction.NORTH,at,false)));}
    public static final class HeldPlayer extends Player {
        ItemStack main, off;
        private HeldPlayer(){ super(null, BlockPos.ZERO, 0, new GameProfile(new UUID(0,1),"probe")); }
        @Override public ItemStack getMainHandItem(){return main;}
        @Override public ItemStack getOffhandItem(){return off;}
        @Override public boolean isSpectator(){return false;}
        @Override public boolean isCreative(){return false;}
    }
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        Field field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);unsafe=(Unsafe)field.get(null);
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // Test-process-only intrusive holders for unregistered fixture blocks.
        field(net.minecraft.core.registries.BuiltInRegistries.BLOCK,"frozen",false);
        field(net.minecraft.core.registries.BuiltInRegistries.BLOCK,"unregisteredIntrusiveHolders",new IdentityHashMap<>());
        var player=(HeldPlayer)unsafe.allocateInstance(HeldPlayer.class);
        var block=(SfcHomeConsoleBlock)unsafe.allocateInstance(SfcHomeConsoleBlock.class);
        Method method=SfcHomeConsoleBlock.class.getDeclaredMethod("useWithoutItem",BlockState.class,Level.class,BlockPos.class,Player.class,BlockHitResult.class);
        method.setAccessible(true);
        var hit=new BlockHitResult(Vec3.ZERO,Direction.NORTH,BlockPos.ZERO,false);
        for(int hand=0;hand<3;hand++){
            player.main=hand!=1?new ItemStack(Items.STICK):ItemStack.EMPTY;
            player.off=hand!=0?new ItemStack(Items.STICK):ItemStack.EMPTY;
            check(method.invoke(block,null,null,BlockPos.ZERO,player,hit)==InteractionResult.PASS,"held item must reach Item.useOn, hand="+hand);
        }
        field(ModItems.AV_CABLE,"holder",Holder.direct(Items.STICK));
        field(cn.piq.sfchome.registry.SfcHomeRegistries.CARTRIDGE,"holder",Holder.direct(Items.PAPER));
        field(cn.piq.sfchome.registry.SfcHomeRegistries.CONTROLLER,"holder",Holder.direct(Items.BONE));
        HomeSystems.register(SfcHomeConsoleBlockEntity.SYSTEM_ID,new HomeSystems.ServerHooks(){public void onInteract(ServerPlayer p,InteractionHand h,HomeSystems.Connection c,BlockHitResult hit){}});
        var cable=(AvCableItem)unsafe.allocateInstance(AvCableItem.class);
        for(int machine=0;machine<3;machine++)for(int display=0;display<6;display++)for(boolean reverse:new boolean[]{false,true}){
            var l=level(false);var c=console(l,machine,new BlockPos(0,0,0));var t=tv(l,display,new BlockPos(4,0,0));var p=wirePlayer(l);
            check(c.kind()==HomeLinkLedger.Kind.CONSOLE&&t.kind()==HomeLinkLedger.Kind.TV,"actual endpoint role");
            check(click(cable,p,reverse?t.getBlockPos():c.getBlockPos())==InteractionResult.CONSUME,"first item click consumed");
            check(p.main.getCount()==4&&c.linkId()==null,"first click does not charge");
            check(click(cable,p,reverse?c.getBlockPos():t.getBlockPos())==InteractionResult.CONSUME,"second item click consumed");
            check(c.linkId()!=null&&c.linkId().equals(t.linkId()),"actual two-way link machine="+machine+" display="+display+" reverse="+reverse+" message="+p.message);
            check(p.main.getCount()==3,"single charge");
            check(HomeHardware.connectedEndpoint(l,t.getBlockPos())==c,"actual connected endpoint identity");
        }
        // Actual held-item/default-block/item sequence on a client fixture.
        var client=level(true);var sfc=console(client,2,BlockPos.ZERO);
        player.main=new ItemStack(Items.STICK);player.off=ItemStack.EMPTY;
        Method itemMethod=SfcHomeConsoleBlock.class.getDeclaredMethod("useItemOn",ItemStack.class,BlockState.class,Level.class,BlockPos.class,Player.class,InteractionHand.class,BlockHitResult.class);itemMethod.setAccessible(true);
        check(itemMethod.invoke(block,player.main,sfc.getBlockState(),client,BlockPos.ZERO,player,InteractionHand.MAIN_HAND,hit)==net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,"AV initially delegates to default block interaction");
        check(method.invoke(block,sfc.getBlockState(),client,BlockPos.ZERO,player,hit)==InteractionResult.PASS,"AV default block interaction yields to Item.useOn");
        var context=new UseOnContext(client,player,InteractionHand.MAIN_HAND,player.main,hit);
        check(cable.useOn(context)==InteractionResult.SUCCESS,"external console client Item.useOn predicts success");
        check(sfc.linkId()==null&&player.main.getCount()==1,"client prediction never mutates link/payment");
        client.missing.add(BlockPos.ZERO);check(cable.useOn(context)==InteractionResult.PASS,"unloaded external not predicted");client.missing.clear();
        field(sfc,"remove",true);check(cable.useOn(context)==InteractionResult.PASS,"removed external not predicted");field(sfc,"remove",false);
        client.entities.clear();check(cable.useOn(context)==InteractionResult.PASS,"non-hardware not predicted");
        for(int display=0;display<6;display++){var l=level(true);tv(l,display,BlockPos.ZERO);check(cable.useOn(new UseOnContext(l,player,InteractionHand.MAIN_HAND,player.main,hit))==InteractionResult.SUCCESS,"existing TV client prediction="+display);}
        // Re-run the actual transaction with invalid physical/permission/identity facts.
        for(int failure=0;failure<9;failure++)for(boolean reverse:new boolean[]{false,true}){
            var l=level(false);var c=console(l,2,BlockPos.ZERO);var t=tv(l,4,new BlockPos(4,0,0));var p=wirePlayer(l);
            BlockPos first=reverse?t.getBlockPos():c.getBlockPos(),second=reverse?c.getBlockPos():t.getBlockPos();
            click(cable,p,first);
            switch(failure){
                case 0->l.denied.add(second);
                case 1->l.missing.add(second);
                case 2->l.denied.add(first);
                case 3->l.missing.add(first);
                case 4->field(l.entities.get(first),"hardwareId",UUID.randomUUID());
                case 5->second=first; // Same physical type is still rejected, never silently reselect.
                case 6->{var part=l.entities.values().stream().filter(v->v instanceof LargeLcdTvPartBlockEntity).findFirst().orElseThrow();l.missing.add(part.getBlockPos());}
                case 7->{var part=l.entities.values().stream().filter(v->v instanceof LargeLcdTvPartBlockEntity).findFirst().orElseThrow();field(part,"owner",UUID.randomUUID());}
                case 8->p.main=new ItemStack(Items.DIRT,4);
            }
            click(cable,p,second);
            check(c.linkId()==null&&t.linkId()==null,"rejected transaction did not attach failure="+failure+" reverse="+reverse);
            check(p.main.getCount()==4,"rejected transaction did not charge");
            check(HomeLinkData.get(l).ledger.snapshots().isEmpty(),"rejected transaction wrote no ledger");
        }
        System.out.println("CHECKS="+checks);
    }
}
