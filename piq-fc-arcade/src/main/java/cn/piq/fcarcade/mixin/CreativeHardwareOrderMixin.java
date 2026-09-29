package cn.piq.fcarcade.mixin;

import cn.piq.fcarcade.registry.CreativeHardwareOrder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

/** Only our tab; tail runs after every addon has populated it. Preserve each stack and search visibility. */
@Mixin(CreativeModeTab.class)
abstract class CreativeHardwareOrderMixin {
    @Shadow private Collection<ItemStack> displayItems;
    @Shadow private Set<ItemStack> displayItemsSearchTab;
    @Inject(method="buildContents",at=@At("TAIL"))
    private void piq$groupHardware(CreativeModeTab.ItemDisplayParameters parameters,CallbackInfo ci){
        var key=BuiltInRegistries.CREATIVE_MODE_TAB.getKey((CreativeModeTab)(Object)this);
        if(key==null||!key.toString().equals("piq_fc_arcade:fc"))return;
        displayItems=CreativeHardwareOrder.sorted(displayItems,s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        var ordered=CreativeHardwareOrder.sorted(displayItemsSearchTab,s->BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        var search=ItemStackLinkedSet.createTypeAndComponentsSet();search.addAll(ordered);displayItemsSearchTab=search;
    }
}
