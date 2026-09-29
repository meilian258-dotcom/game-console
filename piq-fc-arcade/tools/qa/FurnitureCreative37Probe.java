import cn.piq.fcarcade.furniture.*;
import cn.piq.fcarcade.registry.*;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Actual production item/tab callbacks in isolated real registries, without a world or client. */
public final class FurnitureCreative37Probe {
    private static int assertions;
    private static void check(boolean ok,String why) { assertions++;if(!ok)throw new AssertionError(why); }
    public static void main(String[] args)throws Exception {
        var console=System.out;var fc=Path.of(args[0]).toRealPath();
        for(var type:List.of(FurnitureRegistry.class,ModCreativeTabs.class,CreativeTabCatalog.class)) {
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(fc),"production from candidate JAR: "+type.getName());
        }
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        var modList=ModList.of(List.of(),List.of());
        var loaded=ModList.class.getDeclaredMethod("setLoadedMods",List.class);loaded.setAccessible(true);loaded.invoke(modList,List.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var bus=BusBuilder.builder().build();
        ModBlocks.BLOCKS.register(bus);ModItems.ITEMS.register(bus);FurnitureRegistry.register(bus);ModCreativeTabs.TABS.register(bus);
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);constructor.setAccessible(true);
        for(var registry:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE,BuiltInRegistries.ENTITY_TYPE,BuiltInRegistries.CREATIVE_MODE_TAB)) {
            ((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        }
        for(var item:BuiltInRegistries.ITEM)if(item instanceof BlockItem blockItem)blockItem.registerBlocks(Item.BY_BLOCK,item);
        var fcId=ResourceLocation.parse("piq_fc_arcade:fc");
        check(BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(fcId).orElseThrow()==ModCreativeTabs.FC.get(),"same existing FC tab ID");
        check(BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(ResourceLocation.parse("piq_fc_arcade:furniture")).isEmpty(),"no separate furniture tab");
        check(BuiltInRegistries.CREATIVE_MODE_TAB.keySet().stream().filter(id->id.getNamespace().equals("piq_fc_arcade")).count()==1,"exactly one PIQ creative page");
        var actual=new ArrayList<String>();
        CreativeModeTab.Output output=(stack,visibility)-> {
            check(!stack.isEmpty()&&stack.getCount()==1,"one real item per entry");
            check(visibility==CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS,"parent and search visible");
            actual.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        };
        var display=ModCreativeTabs.class.getDeclaredMethod("displayItems",CreativeModeTab.ItemDisplayParameters.class,CreativeModeTab.Output.class);
        display.setAccessible(true);display.invoke(null,null,output);
        var expected=new ArrayList<String>();
        for(var path:CreativeTabCatalog.itemPaths(false))expected.add("piq_fc_arcade:"+path);
        check(expected.size()==18,"existing 18-item FC order unchanged");
        for(var wood:WoodSpecies.values())for(var suffix:List.of("_bench","_stool"))expected.add("piq_fc_arcade:furniture/"+wood.id()+suffix);
        check(actual.equals(expected),"actual callback has original FC entries then all 22 furniture in wood pairs");
        check(actual.size()==40&&new HashSet<>(actual).size()==40,"forty unique actual entries");
        for(var id:actual)check(BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).isPresent(),"registered item: "+id);
        check(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(FurnitureRegistry.FURNITURE_ENTITY.get()).toString().equals("piq_fc_arcade:furniture/furniture"),"BE identity unchanged");
        check(BuiltInRegistries.ENTITY_TYPE.getKey(FurnitureRegistry.SEAT.get()).toString().equals("piq_fc_arcade:furniture/seat"),"seat identity unchanged");
        console.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"items",actual,
                "limits",List.of("Actual FC display callback and registered items; not GUI screenshots or player-world testing.","Optional addon creative event injection is separately retained and audited; not fired by this probe."))));
    }
}
