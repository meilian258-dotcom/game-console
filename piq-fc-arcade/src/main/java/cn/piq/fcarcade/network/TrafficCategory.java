package cn.piq.fcarcade.network;

import net.minecraft.resources.ResourceLocation;

/** Fixed, bounded buckets. Classification never examines or retains game/user bytes. */
public enum TrafficCategory {
    NETPLAY("Netplay"), MEDIA("音画"), FILES("游戏/资源"), OTHER("其他/旧同步");
    private final String label;
    TrafficCategory(String label){this.label=label;}
    public String label(){return label;}
    public static TrafficCategory of(ResourceLocation id){
        String ns=id.getNamespace(),p=id.getPath();
        if(!ns.equals("piq_fc_arcade")&&!ns.equals("piq_sfc_home"))return OTHER;
        if(p.startsWith("netplay_")||p.equals("watch_netplay")||p.equals("cabinet_room_netplay_start"))return NETPLAY;
        if(p.equals("watch_media")||p.equals("watch_stream")||p.equals("cabinet_room_media")||p.equals("cabinet_room_stream")
                ||p.equals("home_hosted_stream")||p.equals("hosted_stream"))return MEDIA;
        if(p.startsWith("rom_")||p.startsWith("skin_")||p.startsWith("game_")||p.startsWith("cover_")||p.equals("editor")||p.equals("editor_action")||p.startsWith("cartridge_")
                &&!p.startsWith("cartridge_save_")&&!p.equals("cartridge_dismantle"))return FILES;
        return OTHER;
    }
}
