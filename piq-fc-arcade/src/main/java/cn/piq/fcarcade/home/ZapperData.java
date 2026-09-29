package cn.piq.fcarcade.home;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Rendering receipt only. Forging a receipt never creates a server lease. */
public final class ZapperData {
    private static final String KEY="PiqZapper";
    private ZapperData(){}
    public static ZapperBinding binding(ItemStack stack){
        if(stack.isEmpty()||stack.getCount()!=1||!(stack.getItem() instanceof HomeZapperItem))return null;
        var t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound(KEY);
        if(!t.hasUUID("Lease")||!t.hasUUID("Console")||!t.hasUUID("Tv")||!t.hasUUID("Link")||!t.contains("ConsolePos",Tag.TAG_LONG)||!t.contains("TvPos",Tag.TAG_LONG))return null;
        try{return new ZapperBinding(t.getLong("Session"),t.getInt("Epoch"),t.getUUID("Lease"),ResourceLocation.parse(t.getString("Dimension")),
                BlockPos.of(t.getLong("ConsolePos")),t.getUUID("Console"),BlockPos.of(t.getLong("TvPos")),t.getUUID("Tv"),t.getUUID("Link"));}
        catch(IllegalArgumentException error){return null;}
    }
    public static boolean matches(ItemStack stack,ZapperBinding binding){return binding!=null&&binding.equals(binding(stack));}
    static void bind(ItemStack stack,ZapperBinding b){
        var t=new CompoundTag();t.putLong("Session",b.sessionId());t.putInt("Epoch",b.epoch());t.putUUID("Lease",b.lease());t.putString("Dimension",b.dimension().toString());
        t.putLong("ConsolePos",b.consolePos().asLong());t.putUUID("Console",b.consoleId());t.putLong("TvPos",b.tvPos().asLong());t.putUUID("Tv",b.tvId());t.putUUID("Link",b.linkId());
        CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.put(KEY,t));
    }
    static void clear(ItemStack stack){CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.remove(KEY));}
}
