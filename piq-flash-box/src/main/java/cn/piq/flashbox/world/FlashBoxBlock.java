package cn.piq.flashbox.world;

import cn.piq.fcarcade.home.HomeApplianceService;
import cn.piq.fcarcade.home.HomeHardware;
import cn.piq.fcarcade.home.HomeSystems;
import cn.piq.flashbox.registry.FlashBoxRegistries;
import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FlashBoxBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<FlashBoxBlock> CODEC = simpleCodec(FlashBoxBlock::new);
    public FlashBoxBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? Block.box(2.6, 0, 2, 13.4, 3, 14) : Block.box(2, 0, 2.6, 14, 3, 13.4);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new FlashBoxBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == FlashBoxRegistries.BOX_ENTITY.get() ? (world, pos, block, entity) -> ((FlashBoxBlockEntity)entity).maintenanceTick() : null;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // Held video cables and unrelated items must still reach their own Item.useOn.
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) {
            var button = HomeApplianceService.tryButton(server, pos, InteractionHand.MAIN_HAND, hit);
            if (button != InteractionResult.PASS) return button;
            return HomeSystems.interact(server, pos, InteractionHand.MAIN_HAND, hit);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        lines.add(Component.translatable("tooltip.piq_flash_box.local_prototype"));
        lines.add(Component.translatable("tooltip.piq_flash_box.connect"));
        lines.add(Component.translatable("tooltip.piq_flash_box.tv_scope"));
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        try { if (state.getBlock() != next.getBlock()) HomeHardware.removed(level, pos); }
        finally { super.onRemove(state, level, pos, next, moving); }
    }
}
