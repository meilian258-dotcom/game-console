package cn.piq.fcarcade.home;

import cn.piq.fcarcade.server.ServerCartridgeService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class FcCartridgeItem extends Item {
    public FcCartridgeItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public Component getName(ItemStack stack) {
        String title = FcCartridgeData.title(stack);
        return title.isEmpty() ? super.getName(stack) : Component.literal(title);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String rom = FcCartridgeData.romSha(stack);
        lines.add(Component.literal(rom.isEmpty() ? "空白 FC 卡带" : "游戏 ROM · " + rom.substring(0, 8)));
        if(!rom.isEmpty())lines.add(Component.literal(switch(FcCartridgeData.saveMode(stack)){
            case NONE->"【不存档】";case PLAYER->"【个人存档】";
            case MACHINE->FcCartridgeData.hasSavedProgress(stack)?"【卡带存档】【已保存进度】":"【卡带存档】";
        }));
        lines.add(Component.translatable("item.piq_fc_arcade.fc_cartridge.computer_hint"));
        lines.add(Component.literal("主手 Shift + 左键：拆开卡壳（背包需一个空格）"));
        lines.add(Component.literal("普通右键 FC 主机：插入已写好的卡带"));
        super.appendHoverText(stack, context, lines, flag);
    }
    @Override public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && stack.getCount() == 1 && FcCartridgeData.id(stack) == null && FcCartridgeData.supportsAssembly(stack)) {
            try { FcCartridgeData.ensureIdentity(stack); }
            catch (IllegalArgumentException ignored) { /* Invalid NBT stays untouched and cannot be dismantled. */ }
        }
        if(level instanceof net.minecraft.server.level.ServerLevel server&&stack.getCount()==1&&level.getGameTime()%80==slot%80&&!(entity instanceof ServerPlayer player&&ServerCartridgeService.editing(player,stack)))
            cn.piq.fcarcade.server.ServerArcadeSessions.refreshCartridgeProgress(server.getServer(),stack);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(stack);
        if (player instanceof ServerPlayer serverPlayer) HomeFeedback.show(serverPlayer, "computer_required");
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof CartridgeComputerBlock) {
            if (player instanceof ServerPlayer serverPlayer)
                ServerCartridgeService.openAt(serverPlayer, context.getHand(), context.getClickedPos());
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) HomeFeedback.show(serverPlayer, "computer_required");
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        if (player instanceof ServerPlayer serverPlayer)
            return HomeHardware.insertCartridge(serverPlayer, context.getClickedPos(), context.getHand(),
                    new net.minecraft.world.phys.BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside()));
        // Match the server's consumed console interaction so offhand AV tools do not fire as well.
        if (context.getLevel().getBlockEntity(context.getClickedPos()) instanceof HomeConsoleBlockEntity
                || context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof SuborPartBlock)
            return InteractionResult.SUCCESS;
        return InteractionResult.PASS;
    }
}
