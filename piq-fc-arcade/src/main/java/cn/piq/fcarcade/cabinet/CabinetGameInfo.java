package cn.piq.fcarcade.cabinet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;

/** Read-only selected game metadata, never a filesystem path or launch capability. */
public final class CabinetGameInfo {
    private CabinetGameInfo(){}
    public static String describe(MinecraftServer server,CabinetTarget target,ResourceLocation backend){
        var primary=CabinetLinks.master(server,target);
        var manifest=CabinetSharedGameData.get(server).find(primary,backend.toString());
        String name=manifest==null?"未绑定服务器游戏":CabinetGameProfiles.get(server).find(manifest).label(manifest.files().getFirst().name());
        return name+(CabinetRooms.hasTarget(server,primary)?" · 会话已开启":" · 未运行");
    }
    public static void check(String value){if(value==null||value.length()>192||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Game info");}
}
