package cn.piq.sfchome.data;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
public final class SfcControllerData {
    private SfcControllerData(){}
    public static boolean isController(ItemStack s){return s!=null&&s.is(SfcHomeRegistries.CONTROLLER.get());}
    private static CompoundTag tag(ItemStack s){var d=s.get(DataComponents.CUSTOM_DATA);return d==null?new CompoundTag():d.copyTag();}
    public static UUID leaseId(ItemStack s){var t=tag(s);return t.hasUUID("SfcLease")?t.getUUID("SfcLease"):null;}
    public static int port(ItemStack s){return tag(s).getInt("SfcPort");}
    public static ItemStack create(UUID lease,int port){var s=new ItemStack(SfcHomeRegistries.CONTROLLER.get());var t=new CompoundTag();t.putUUID("SfcLease",lease);t.putInt("SfcPort",port);s.set(DataComponents.CUSTOM_DATA,CustomData.of(t));return s;}
}
