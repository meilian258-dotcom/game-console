package cn.piq.fcarcade.home;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.util.UUID;

/** A physical board owns its save identity; the shell, player and machine do not. */
public final class CartridgeSaveIdentity {
    private CartridgeSaveIdentity() {}
    public static String key(UUID card){if(card==null||card.equals(new UUID(0,0)))throw new IllegalArgumentException("卡带身份无效");return "cartridge|"+card;}
    public static boolean owns(String key,UUID card){
        if(card==null||key==null||card.equals(new UUID(0,0)))return false;
        if(cn.piq.fcarcade.netplay.FcNetplaySaves.key(false,true,key(card)).equals(key))return true;
        for(var variant:NesCoreVariant.values())if(variant.saveKey(key(card)).equals(key))return true;
        for(boolean gun:new boolean[]{false,true})if(cn.piq.fcarcade.netplay.FcNetplaySaves.key(gun,key(card)).equals(key))return true;
        return false;
    }
}
