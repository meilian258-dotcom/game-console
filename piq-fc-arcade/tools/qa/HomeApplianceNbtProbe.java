package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.ModBlocks;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.registry.ModItems;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Real registered production constructors and NBT. No Unsafe, fake world/player or replaced holder. */
public final class HomeApplianceNbtProbe {
    private static int assertions;
    private static HolderLookup.Provider registries;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private static void register()throws Exception{
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var bus=BusBuilder.builder().build();
        ModBlocks.BLOCKS.register(bus);ModItems.ITEMS.register(bus);ModBlockEntities.BLOCK_ENTITIES.register(bus);
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);constructor.setAccessible(true);
        for(var registry:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE)){
            // The real NeoForge registration API, in this isolated process after vanilla bootstrap.
            ((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        }
        registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        check(BuiltInRegistries.BLOCK.getKey(ModBlocks.RETRO_TV.get()).toString().equals("piq_fc_arcade:retro_tv"),"real registered TV block");
        check(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ModBlockEntities.HOME_TV.get()).toString().equals("piq_fc_arcade:home_tv"),"real registered TV BE");
        check(BuiltInRegistries.ITEM.getKey(ModItems.FC_ZAPPER.get()).toString().equals("piq_fc_arcade:fc_zapper"),"real registered gun item");
    }
    private static HomeTvBlockEntity television(){var tv=new HomeTvBlockEntity(BlockPos.ZERO,ModBlocks.RETRO_TV.get().defaultBlockState());check(tv.getLevel()==null,"no fake/live world assigned");return tv;}
    private static CompoundTag save(HomeTvBlockEntity tv){var t=new CompoundTag();tv.saveAdditional(t,registries);return t;}
    private static void televisionNbt(){
        var fresh=television();check(!fresh.powered(),"new instance starts off");check(fresh.volume()==60,"new default volume");check(!fresh.signalPresent()&&fresh.audioGain()==0,"new off has no signal/audio");
        var legacy=television();legacy.loadAdditional(new CompoundTag(),registries);check(legacy.powered(),"legacy no fields retains old powered look");check(legacy.volume()==60&&!legacy.signalPresent(),"legacy default volume/no stale signal");
        for(boolean power:new boolean[]{false,true})for(boolean signal:new boolean[]{false,true})for(int volume:new int[]{Integer.MIN_VALUE,-1,0,10,60,100,101,Integer.MAX_VALUE}){
            var t=new CompoundTag();t.putBoolean("TvPowered",power);t.putBoolean("TvSignal",signal);t.putInt("TvVolume",volume);t.putBoolean("CrtScanlines",true);t.putBoolean("LargeTvStructure",true);
            var identity=UUID.randomUUID();t.putUUID("HardwareId",identity);
            var tv=television();tv.loadAdditional(t,registries);
            check(tv.powered()==power,"power NBT");check(tv.signalPresent()==(power&&signal),"signal only while powered");
            check(tv.volume()==Math.max(0,Math.min(100,volume)),"bounded volume");check(tv.scanlinesEnabled()&&tv.structureInstalled(),"existing TV NBT preserved");
            check(tv.hardwareId().equals(identity),"hardware identity preserved");
            check(tv.audioGain()==(power?tv.volume()/100f:0),"off always silent");
            var stored=save(tv);var copy=television();copy.loadAdditional(stored,registries);
            check(copy.powered()==tv.powered()&&copy.volume()==tv.volume()&&copy.signalPresent()==tv.signalPresent(),"exact presentation round trip");
            check(copy.hardwareId().equals(identity)&&copy.scanlinesEnabled(),"identity and CRT round trip");
            check(!tv.setPower(!power)&&!tv.setVolume(50),"non-authoritative worldless setter rejected");
            tv.signal(!signal);check(tv.signalPresent()==(power&&signal),"non-authoritative signal setter rejected");
        }
    }
    private static ZapperStandBlockEntity stand(){return new ZapperStandBlockEntity(BlockPos.ZERO,ModBlocks.ZAPPER_STAND.get().defaultBlockState());}
    private static CompoundTag save(ZapperStandBlockEntity b){var t=new CompoundTag();b.saveAdditional(t,registries);return t;}
    private static void standNbt(){
        var b=stand();check(!b.occupied()&&b.loan()==null,"new actual BE empty");check(!save(b).contains("Gun"),"no free gun serialized");
        var gun=new ItemStack(ModItems.FC_ZAPPER.get());CustomData.update(DataComponents.CUSTOM_DATA,gun,t->t.putString("Keep","original"));
        check(b.dock.deposit(gun,null),"deposit actual gun");var saved=save(b);var copy=stand();copy.loadAdditional(saved,registries);
        check(copy.identity().equals(b.identity()),"stand ID round trip");check(copy.occupied()&&copy.loan()==null,"stored not simultaneously loaned");
        var actual=copy.dock.stored();check(actual.getCount()==1&&actual.getItem()==ModItems.FC_ZAPPER.get(),"actual registered stack parser");
        check(ItemStack.isSameItemSameComponents(actual,gun),"gun metadata untouched through real codec");
        var borrower=UUID.randomUUID();check(copy.dock.take(borrower)==actual,"actual original ItemStack moved by identity");var loan=copy.loan();
        var borrowed=stand();borrowed.loadAdditional(save(copy),registries);check(!borrowed.occupied()&&borrowed.loan().equals(loan),"borrowed NBT contains receipt not replacement");
        check(borrowed.dock.remove()==null&&borrowed.dock.remove()==null,"actual BE borrowed destruction cannot issue gun");
        var origin=new ZapperStandLinks.End("minecraft:overworld",0,64,0,copy.identity());ZapperStandOrigin.bind(actual,origin,loan.id());
        check(ZapperStandOrigin.read(actual).equals(new ZapperStandOrigin.Receipt(origin,loan.id())),"real custom data receipt");
        check(actual.get(DataComponents.CUSTOM_DATA).copyTag().getString("Keep").equals("original"),"receipt preserves unrelated metadata");
        ZapperStandOrigin.clear(actual);check(!ZapperStandOrigin.present(actual)&&ZapperStandOrigin.read(actual)==null,"receipt cleared exactly");
        check(actual.get(DataComponents.CUSTOM_DATA).copyTag().getString("Keep").equals("original"),"clear preserves other metadata");
        var forged=save(b);forged.getCompound("Gun").putInt("count",2);var invalid=stand();invalid.loadAdditional(forged,registries);check(!invalid.occupied(),"count2 NBT cannot populate dock");
        check(b.onlyOpCanSetNbt(),"untrusted placement cannot import BE inventory");
    }
    public static void main(String[] args)throws Exception{
        check(args.length==1,"exact FC JAR");var expected=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(HomeTvBlockEntity.class,TelevisionState.class,ZapperStandBlockEntity.class,ZapperDock.class,ZapperStandOrigin.class,ModBlocks.class,ModItems.class,ModBlockEntities.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"actual final class origin "+c.getName());
        register();televisionNbt();standNbt();
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_neoforge_register_event\":true,\"actual_production_constructors\":true,\"actual_minecraft_nbt\":true,\"unsafe_or_fake_holders\":false,\"production_origin\":\"supplied-jar-only\",\"production_compiled\":false,\"minecraft_world_started\":false,\"full_fml_discovery_test\":false}");
    }
}
