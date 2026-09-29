package cn.piq.fcarcade.home;

import cn.piq.fcarcade.server.ServerCartridgeAssemblyService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.UUID;

/** Bare playable PCB. Never reveals ROM SHA, title or cover through its item presentation. */
public final class FcCartridgeBoardItem extends Item {
    public FcCartridgeBoardItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public Component getName(ItemStack stack) { return Component.translatable("item.piq_fc_arcade.fc_cartridge_board"); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("裸电路板 · 无外壳标签"));
        lines.add(Component.literal("普通右键 FC / 小霸王：插入电路板"));
        lines.add(Component.literal("主手电路板 + 副手空壳，Shift + 右键：合回卡带"));
        super.appendHoverText(stack, context, lines, flag);
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && stack.getCount() == 1 && FcCartridgeData.id(stack) == null && FcCartridgeData.supportsAssembly(stack)) {
            // Creative/give blank boards are initialized once; no ROM-derived appearance or reroll.
            FcCartridgeData.writeBoard(stack, new CartridgeParts.Board(UUID.randomUUID(), "", "",
                    level.random.nextInt(CartridgeParts.VARIANT_COUNT), 0));
        }
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown() || hand != InteractionHand.MAIN_HAND || !FcCartridgeData.isShell(player.getOffhandItem()))
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (player instanceof ServerPlayer server) ServerCartridgeAssemblyService.combine(server, hand);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (context.getHand() != InteractionHand.MAIN_HAND || !FcCartridgeData.isShell(player.getOffhandItem())) return InteractionResult.PASS;
            if (player instanceof ServerPlayer server) ServerCartridgeAssemblyService.combine(server, context.getHand());
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (player instanceof ServerPlayer server) return HomeHardware.insertCartridge(server, context.getClickedPos(), context.getHand(),
                new net.minecraft.world.phys.BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside()));
        return context.getLevel().getBlockEntity(context.getClickedPos()) instanceof HomeConsoleBlockEntity
                || context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof SuborPartBlock
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
