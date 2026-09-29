package cn.piq.fcarcade.home;

import cn.piq.fcarcade.cabinet.CabinetLinkCableItem;
import cn.piq.fcarcade.registry.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Actual item registry, inheritance/tooltip dispatch and stack codec; no world or invented authority. */
public final class DataCableItemProbe {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("final FC JAR");var jar=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(ModItems.class,ModBlocks.class,ZapperStandCableItem.class,CabinetLinkCableItem.class,AvCableItem.class,CreativeTabCatalog.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final production origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();var bus=BusBuilder.builder().build();
        ModBlocks.BLOCKS.register(bus);ModItems.ITEMS.register(bus);ModBlockEntities.BLOCK_ENTITIES.register(bus);
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);constructor.setAccessible(true);
        for(var registry:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE)){
            ((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        }
        var lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var main=ModItems.ZAPPER_STAND_CABLE.get();var alias=ModItems.CABINET_LINK_CABLE.get();
        check(main!=alias,"old id not remapped away");
        for(Item item:List.of(main,alias)) {
            check(item instanceof ZapperStandCableItem,"same authoritative service item family");
            check(item.getClass().getMethod("useOn",UseOnContext.class).getDeclaringClass()==ZapperStandCableItem.class,"same actual implementation inherited");
            var stack=new ItemStack(item);check(stack.getMaxStackSize()==1,"single physical tool");
            var saved=stack.save(lookup);var restored=ItemStack.parse(lookup,saved).orElseThrow();
            check(restored.getItem()==item&&restored.getCount()==1,"real saved item id roundtrip");
            for(TooltipFlag flag:List.of(TooltipFlag.NORMAL,TooltipFlag.ADVANCED)){
                var lines=new ArrayList<Component>();item.appendHoverText(stack,Item.TooltipContext.EMPTY,lines,flag);
                check(lines.size()==3,"same three line tooltip");
                String[] expected={"connect","disconnect","permissions"};
                for(int i=0;i<3;i++)check(lines.get(i).getContents() instanceof TranslatableContents text
                        &&text.getKey().equals("tooltip.piq_fc_arcade.data_cable."+expected[i]),"real tooltip dispatch");
            }
        }
        check(BuiltInRegistries.ITEM.getKey(main).toString().equals("piq_fc_arcade:zapper_stand_cable"),"stable main id");
        check(BuiltInRegistries.ITEM.getKey(alias).toString().equals("piq_fc_arcade:cabinet_link_cable"),"stable compatibility id");
        Item av=ModItems.AV_CABLE.get();check(!(av instanceof ZapperStandCableItem),"AV remains separate");
        var lines=new ArrayList<Component>();av.appendHoverText(new ItemStack(av),Item.TooltipContext.EMPTY,lines,TooltipFlag.NORMAL);
        check(lines.size()==2,"AV tooltip");check(((TranslatableContents)lines.get(1).getContents()).getKey().equals("tooltip.piq_fc_arcade.av_cable.disconnect"),"AV disconnect hint");
        for(boolean optional:new boolean[]{false,true}){var paths=CreativeTabCatalog.itemPaths(optional);
            check(!paths.contains("cabinet_link_cable")&&paths.stream().filter("zapper_stand_cable"::equals).count()==1,"only one creative data cable");}
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_neoforge_registration\":true,\"actual_item_stack_codec\":true,\"actual_inherited_useOn_and_tooltip\":true,\"production_compiled\":false,\"minecraft_world_started\":false}");
    }
}
