package cn.piq.fcarcade.client.ui;

import cn.piq.fcarcade.home.AvCableItem;
import cn.piq.fcarcade.home.FcCartridgeItem;
import cn.piq.fcarcade.home.ZapperStandCableItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.util.ArrayList;

/** Client presentation only; preserve item names, lore and other mods' tooltip contributions. */
@EventBusSubscriber(modid="piq_fc_arcade", value=Dist.CLIENT)
public final class DeviceItemTooltips {
    private DeviceItemTooltips() {}
    @SubscribeEvent public static void tooltip(ItemTooltipEvent event) {
        var item=event.getItemStack().getItem();
        String kind=item instanceof FcCartridgeItem?"cartridge":item instanceof AvCableItem?"video":item instanceof ZapperStandCableItem?"data":null;
        if(kind==null)return;
        var lines=event.getToolTip();var details=new ArrayList<Component>();
        // Never remove the first line (the item name), even if it happens to match a hint.
        for(int i=1;i<lines.size();){
            var line=lines.get(i);
            boolean own=line.getSiblings().isEmpty()&&(line.getContents() instanceof TranslatableContents translated
                    ?DeviceTooltipPolicy.detailKey(kind,translated.getKey()):DeviceTooltipPolicy.detailLiteral(kind,line.getString()));
            if(own)details.add(lines.remove(i));else i++;
        }
        int insertion=Math.min(1,lines.size());
        lines.add(insertion++,Component.translatable("tooltip.piq_fc_arcade.short."+kind).withStyle(ChatFormatting.GRAY));
        if(Screen.hasShiftDown())lines.addAll(insertion,details);
        else lines.add(insertion,Component.translatable("tooltip.piq_fc_arcade.shift_details").withStyle(ChatFormatting.DARK_GRAY));
    }
}
