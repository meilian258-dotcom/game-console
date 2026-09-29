package cn.piq.fcarcade.home;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Borrow receipt is descriptive, never authority without the live stand's exact loan. */
public final class ZapperStandOrigin {
    private static final String KEY="PiqZapperStand";
    public record Receipt(ZapperStandLinks.End stand,UUID loan) {}
    private ZapperStandOrigin(){}
    public static boolean present(ItemStack stack){return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains(KEY);}
    public static Receipt read(ItemStack stack){if(stack.getCount()!=1||!(stack.getItem() instanceof HomeZapperItem))return null;
        var t=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getCompound(KEY);
        if(!t.hasUUID("Stand")||!t.hasUUID("Loan")||!t.contains("Pos",Tag.TAG_LONG))return null;
        try{var p=BlockPos.of(t.getLong("Pos"));return new Receipt(new ZapperStandLinks.End(t.getString("Dim"),p.getX(),p.getY(),p.getZ(),t.getUUID("Stand")),t.getUUID("Loan"));}
        catch(IllegalArgumentException invalid){return null;}}
    static void bind(ItemStack stack,ZapperStandLinks.End stand,UUID loan){var t=new CompoundTag();t.putString("Dim",stand.dimension());t.putLong("Pos",new BlockPos(stand.x(),stand.y(),stand.z()).asLong());t.putUUID("Stand",stand.id());t.putUUID("Loan",loan);
        CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.put(KEY,t));}
    static void clear(ItemStack stack){CustomData.update(DataComponents.CUSTOM_DATA,stack,data->data.remove(KEY));}
}
