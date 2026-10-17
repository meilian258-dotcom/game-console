// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.registry;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Shared catalog ordering after ordinary addon callbacks. Unknown entries retain their relative order. */
public final class CreativeHardwareOrder {
    private CreativeHardwareOrder(){}
    private static final List<String> ORDER=List.of(
        "piq_fc_arcade:famicom_console","piq_fc_arcade:subor_console","piq_sfc_arcade:sfc_arcade","piq_gba:handheld",
        "piq_fc_arcade:legacy_fc_arcade","piq_fc_arcade:portrait_cabinet","piq_fc_arcade:dual_cabinet","piq_fc_arcade:arcade_coin",
        "piq_fc_arcade:retro_tv","piq_fc_arcade:gray_crt_tv","piq_fc_arcade:red_crt_tv","piq_fc_arcade:panel_tv_2","piq_fc_arcade:panel_tv_3",
        "piq_computer:computer_black","piq_computer:computer_white",
        "piq_computer:motherboard","piq_computer:cpu","piq_computer:cooler","piq_computer:ram","piq_computer:gpu","piq_computer:hdd","piq_computer:psu",
        "piq_computer:keyboard_mouse_black","piq_computer:keyboard_mouse_white","piq_computer:connector",
        "piq_fc_arcade:fc_cartridge","piq_fc_arcade:fc_cartridge_board","piq_fc_arcade:fc_cartridge_shell","piq_fc_arcade:cartridge_computer",
        "piq_fc_arcade:av_cable","piq_fc_arcade:fc_zapper","piq_fc_arcade:zapper_stand","piq_fc_arcade:zapper_stand_cable",
        "piq_fc_arcade:tv_remote","piq_fc_arcade:debug_screwdriver","piq_fc_arcade:admin_terminal");
    public static boolean hidden(String id){return id.equals("piq_pvz:player_box");}
    public static int rank(String id){
        int index=ORDER.indexOf(id);if(index>=0)return index;
        if(id.startsWith("piq_fc_arcade:furniture/"))return id.endsWith("_bench")?100:101;
        if(id.startsWith("piq_fc_arcade:waterframes_"))return 120;
        return 200;
    }
    public static <T> List<T> sorted(Collection<T> entries,java.util.function.Function<T,String> id){
        return entries.stream().filter(e->!hidden(id.apply(e))).sorted(Comparator.comparingInt(e->rank(id.apply(e)))).toList();
    }

    /** Registered at LOWEST: FML dispatches each priority across all mod buses before the next priority. */
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(ModCreativeTabs.FC.getKey())) return;
        // The public views are read-only. Snapshot before using the event's supported mutation API.
        // Process each visibility independently so parent-only/search-only entries stay that way.
        reorder(event, event.getParentEntries(), CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        reorder(event, event.getSearchEntries(), CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
    }

    private static void reorder(BuildCreativeModeTabContentsEvent event, Collection<ItemStack> entries,
                                CreativeModeTab.TabVisibility visibility) {
        var original = List.copyOf(entries);
        var ordered = sorted(original, stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        for (var stack : original) event.remove(stack, visibility);
        for (var stack : ordered) event.accept(stack, visibility);
    }
}
