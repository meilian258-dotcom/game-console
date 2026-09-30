package cn.piq.fcarcade.home.content;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Versioned content identity only; no ROM bytes, paths, core binaries or client authority. */
public final class ContentCardData {
    private static final String KEY="GameConsoleContentCard";
    public static ContentCardStore.Entry read(ItemStack stack,ResourceLocation system){
        var data=stack.get(DataComponents.CUSTOM_DATA);if(data==null)return null;
        var t=data.copyTag().getCompound(KEY);
        if(t.getInt("Version")!=1||!system.toString().equals(t.getString("System")))return null;
        try{return new ContentCardStore.Entry(t.getString("Hash"),t.getString("File"),t.getInt("Size"));}
        catch(IllegalArgumentException invalid){return null;}
    }
    public static String title(ItemStack stack){var data=stack.get(DataComponents.CUSTOM_DATA);return data==null?"":data.copyTag().getCompound(KEY).getString("Title");}
    public static String cover(ItemStack stack){
        var t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound(KEY);
        String hash=t.getString("Cover");return cn.piq.fcarcade.home.CartridgeLimits.validHash(hash)?hash:"";
    }
    /** Legacy content cards saved locally; preserve that default instead of silently erasing progress. */
    public static int saveMode(ItemStack stack){
        var t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound(KEY);
        return t.contains("SaveMode",3)&&t.getInt("SaveMode")==0?0:2;
    }
    public static void cover(ItemStack stack,String hash){
        cn.piq.fcarcade.home.CartridgeLimits.hashOrEmpty(hash);metadata(stack,"Cover",hash,-1);
    }
    public static void saveMode(ItemStack stack,int mode){if(mode!=0&&mode!=2)throw new IllegalArgumentException("卡带服务器存档尚未接入");metadata(stack,null,null,mode);}
    private static void metadata(ItemStack stack,String key,String value,int mode){
        if(stack.getCount()!=1)throw new IllegalArgumentException("需要一张卡带");
        var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();var t=data.getCompound(KEY);
        if(key!=null)t.putString(key,value);if(mode>=0)t.putInt("SaveMode",mode);data.put(KEY,t);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    public static void write(ItemStack stack,ResourceLocation system,ContentCardStore.Entry entry,String title){
        if(stack.getCount()!=1||title==null||title.length()>128||title.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid content card");
        var old=stack.get(DataComponents.CUSTOM_DATA);var data=old==null?new CompoundTag():old.copyTag();var t=new CompoundTag();
        t.putString("Cover",cover(stack));t.putInt("SaveMode",saveMode(stack));
        t.putInt("Version",1);t.putString("System",system.toString());t.putString("Hash",entry.hash());t.putString("File",entry.name());t.putInt("Size",entry.size());t.putString("Title",title);
        data.put(KEY,t);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    private ContentCardData(){}
}
