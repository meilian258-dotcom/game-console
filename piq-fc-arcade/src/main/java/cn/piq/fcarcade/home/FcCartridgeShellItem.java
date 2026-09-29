package cn.piq.fcarcade.home;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.UUID;

/** Detached empty housing: only its original cover travels with it, never ROM content. */
public final class FcCartridgeShellItem extends Item {
    public FcCartridgeShellItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public Component getName(ItemStack stack) { return Component.translatable("item.piq_fc_arcade.fc_cartridge_shell"); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("空外壳 · 保留原封面，不含游戏"));
        lines.add(Component.literal("副手拿外壳，主手拿电路板，Shift + 右键合回"));
        super.appendHoverText(stack, context, lines, flag);
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && stack.getCount() == 1 && FcCartridgeData.id(stack) == null && FcCartridgeData.supportsAssembly(stack))
            FcCartridgeData.writeShell(stack, new CartridgeParts.Shell(UUID.randomUUID(), FcCartridgeData.coverSha(stack)));
    }
}
