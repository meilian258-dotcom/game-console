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
    public static void write(ItemStack stack,ResourceLocation system,ContentCardStore.Entry entry,String title){
        if(stack.getCount()!=1||title==null||title.length()>128||title.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid content card");
        var old=stack.get(DataComponents.CUSTOM_DATA);var data=old==null?new CompoundTag():old.copyTag();var t=new CompoundTag();
        t.putInt("Version",1);t.putString("System",system.toString());t.putString("Hash",entry.hash());t.putString("File",entry.name());t.putInt("Size",entry.size());t.putString("Title",title);
        data.put(KEY,t);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    private ContentCardData(){}
}
