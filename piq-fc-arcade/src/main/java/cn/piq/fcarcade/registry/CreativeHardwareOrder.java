package cn.piq.fcarcade.registry;

import java.util.*;

/** Shared catalog ordering, applied after ALL addon callbacks. Unknown entries remain stable at the end. */
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
}
