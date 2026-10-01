package cn.piq.gba.item;

import cn.piq.gba.GbaMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/** Vanilla container component carries the real card, preserving its data on eject. */
public final class GbaCartridgeSlot {
    public static final UUID EMPTY_ID=new UUID(0,0);
    public static UUID id(ItemStack machine){var d=machine.get(DataComponents.CUSTOM_DATA);return d!=null&&d.copyTag().hasUUID("GbaDeviceId")?d.copyTag().getUUID("GbaDeviceId"):EMPTY_ID;}
    public static void identify(ItemStack machine){if(!id(machine).equals(EMPTY_ID))return;var d=machine.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();d.putUUID("GbaDeviceId",UUID.randomUUID());machine.set(DataComponents.CUSTOM_DATA,CustomData.of(d));}
    public static boolean valid(ItemStack machine){
        var c=machine.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY);
        return c.getSlots()<=1&&(c.getSlots()==0||c.getStackInSlot(0).isEmpty()||(c.getStackInSlot(0).is(GbaMod.CARTRIDGE.get())&&c.getStackInSlot(0).getCount()==1));
    }
    public static ItemStack card(ItemStack machine){return valid(machine)?machine.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyOne():ItemStack.EMPTY;}
    public static void set(ItemStack machine,ItemStack card){
        if(!machine.is(GbaMod.HANDHELD.get())||machine.getCount()!=1||(!card.isEmpty()&&(!card.is(GbaMod.CARTRIDGE.get())||card.getCount()!=1)))throw new IllegalArgumentException("Invalid GBA card slot");
        identify(machine);machine.set(DataComponents.CONTAINER,card.isEmpty()?ItemContainerContents.EMPTY:ItemContainerContents.fromItems(List.of(card.copy())));
    }
    private GbaCartridgeSlot(){}
}
