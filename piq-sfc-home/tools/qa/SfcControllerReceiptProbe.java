package cn.piq.sfchome.world;

import cn.piq.sfchome.data.SfcControllerData;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import cn.piq.sfchome.server.*;
import cn.piq.sfchome.client.SfcControlGrantGate;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Final production ItemStack and BE code, actual registration; no fake world/player/core. */
public final class SfcControllerReceiptProbe {
    static int assertions;static HolderLookup.Provider registries;
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static UUID identity(ItemStack item){return SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null;}
    static void register()throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var bus=BusBuilder.builder().build();SfcHomeRegistries.register(bus);
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);constructor.setAccessible(true);
        for(var registry:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE)){
            ((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        }
        registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        check(BuiltInRegistries.ITEM.getKey(SfcHomeRegistries.CONTROLLER.get()).toString().equals("piq_sfc_home:controller"),"real registered controller, no substituted holder");
    }
    static SfcHomeConsoleBlockEntity console(){var result=new SfcHomeConsoleBlockEntity(BlockPos.ZERO,SfcHomeRegistries.CONSOLE.get().defaultBlockState());check(result.getLevel()==null,"no fake/live world");return result;}
    static void items(){
        for(int port=0;port<2;port++)for(int index=0;index<80;index++){
            UUID id=new UUID(port+1,index+1),otherId=UUID.randomUUID();var hand=SfcControllerData.create(id,port);var other=SfcControllerData.create(otherId,1-port);
            check(identity(hand).equals(id)&&SfcControllerData.port(hand)==port,"created bound controller");
            var container=new SimpleContainer(3);container.setItem(0,hand);container.setItem(1,other);
            var slot=new Slot(container,0,0,0);var alias=new Slot(container,0,0,0);
            check(SfcControllerInventory.unique(id,List.of(slot.getItem(),alias.getItem(),other),SfcControllerReceiptProbe::identity,ItemStack::getCount)==hand,"actual Slot aliases one stack");
            var cursor=hand.copy();var duplicate=hand.copy();var impostor=new ItemStack(Items.STICK);impostor.set(DataComponents.CUSTOM_DATA,hand.get(DataComponents.CUSTOM_DATA));
            check(SfcControllerInventory.unique(id,List.of(hand,cursor),SfcControllerReceiptProbe::identity,ItemStack::getCount)==null,"actual copied stack is duplicate, not alias");
            check(SfcControllerInventory.unique(id,List.of(cursor,other,impostor),SfcControllerReceiptProbe::identity,ItemStack::getCount)==cursor,"actual cursor replacement may be rebound only once");
            int removed=SfcControllerInventory.revoke(id,List.of(slot.getItem(),alias.getItem(),cursor,duplicate,other,impostor),SfcControllerReceiptProbe::identity,item->item.setCount(0));
            check(removed==3,"all distinct real copies revoked exactly once");check(hand.isEmpty()&&cursor.isEmpty()&&duplicate.isEmpty(),"actual ItemStacks emptied");
            check(!other.isEmpty()&&!impostor.isEmpty(),"other lease and same NBT wrong type retained");
            var encoded=other.save(registries);var loaded=ItemStack.parseOptional(registries,(CompoundTag)encoded);
            check(ItemStack.isSameItemSameComponents(other,loaded),"other port item NBT unchanged");
            check(SfcControllerAuthority.withinCableDistance(36)&&!SfcControllerAuthority.withinCableDistance(Math.nextUp(36.0)),"exact cable boundary");
        }
    }
    static void nbt(){
        for(int mask=0;mask<4;mask++)for(int invalid=0;invalid<5;invalid++){
            var input=new CompoundTag();input.putInt("SfcLeasedMask",mask);UUID[] players={UUID.randomUUID(),UUID.randomUUID()},leases={UUID.randomUUID(),UUID.randomUUID()};
            for(int port=0;port<2;port++){input.putUUID("SfcControllerPlayer"+port,players[port]);input.putUUID("SfcControllerLease"+port,leases[port]);}
            if(invalid==1)input.remove("SfcControllerPlayer0");if(invalid==2)input.remove("SfcControllerLease0");
            if(invalid==3)input.putIntArray("SfcControllerPlayer0",new int[]{1,2});if(invalid==4)input.putString("SfcControllerLease0","wrong-type");
            var c=console();c.loadAdditional(input,registries);
            for(int port=0;port<2;port++){
                boolean visible=(mask&(1<<port))!=0&&(port==1||invalid==0);
                check(Objects.equals(c.controllerVisualPlayer(port),visible?players[port]:null),"exact player receipt, complete UUID pair required");
                check(Objects.equals(c.controllerVisualLease(port),visible?leases[port]:null),"exact lease receipt, no stale other port");
            }
            var wire=c.getUpdateTag(registries);var copy=console();copy.loadAdditional(wire,registries);
            for(int port=0;port<2;port++)check(Objects.equals(c.controllerVisualPlayer(port),copy.controllerVisualPlayer(port))&&Objects.equals(c.controllerVisualLease(port),copy.controllerVisualLease(port)),"existing updateTag roundtrip carries receipts");
            c.setControllerVisual(0,UUID.randomUUID(),UUID.randomUUID());check(Objects.equals(c.controllerVisualLease(0),copy.controllerVisualLease(0)),"worldless setter cannot authorize/change receipt");
            c.loadAdditional(new CompoundTag(),registries);var cleared=c.getUpdateTag(registries);check(c.controllerVisualLease(0)==null&&c.controllerVisualLease(1)==null,"missing metadata clears old client receipt");
            check(!cleared.contains("SfcControllerPlayer0")&&!cleared.contains("SfcControllerLease1"),"empty update does not persist stale receipt");
            check(c.controllerVisualPlayer(-1)==null&&c.controllerVisualLease(2)==null,"invalid ports return null");
        }
    }
    static void idleRuntime(){
        var grants=new SfcControlGrantGate();var connection=new Object();UUID physical=UUID.randomUUID();
        check(!grants.mayGrant(connection,1,1,physical),"physical receipt alone has no runtime authority");
        for(long session=1;session<=80;session++){
            grants.bind(connection,session,1);check(grants.mayGrant(connection,session,1,physical),"same physical controller may join next powered runtime");
            check(!grants.mayGrant(connection,session-1,1,physical),"previous powered runtime cannot send controls");
            check(grants.retire(connection,session,1,physical)&&!grants.mayGrant(connection,session,1,physical),"actual return cannot be replayed within same runtime");
            grants.clear();check(!grants.mayGrant(connection,session,1,physical),"shutdown clears runtime, not physical identity");
        }
    }
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(SfcControllerData.class,SfcControllerAuthority.class,SfcControllerInventory.class,SfcHomeConsoleBlockEntity.class,SfcControlGrantGate.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"production origin from input SFC JAR only");
        register();items();nbt();idleRuntime();
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"input-jar-only\",\"actual_registered_items\":true,\"actual_itemstack_and_nbt\":true,\"minecraft_world_or_player_started\":false,\"actual_server_runtime_transitions_exercised\":false,\"native_core_started\":false}");
    }
}
