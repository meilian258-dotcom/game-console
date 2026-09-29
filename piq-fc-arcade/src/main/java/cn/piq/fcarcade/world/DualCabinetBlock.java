package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

/** Independent two-player cabinet shell; all ROMs, saves and sessions remain ordinary FC arcade flows. */
public final class DualCabinetBlock extends FcArcadeBlock implements EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty COMPACT =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("compact");
    public static final MapCodec<DualCabinetBlock> CODEC = simpleCodec(DualCabinetBlock::new);
    public DualCabinetBlock(Properties properties) {
        super(properties, ArcadeMode.LOCKSTEP, ArcadeDisplayStyle.DUAL_CABINET);
        // Missing state in pre-59 worlds stays legacy; only new placement is compact.
        registerDefaultState(defaultBlockState().setValue(COMPACT, false));
    }
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(COMPACT);
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override public boolean supportsFormation() { return false; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.ENTITYBLOCK_ANIMATED; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var state = super.getStateForPlacement(context);
        if (state != null) state = state.setValue(COMPACT, true);
        return state != null && DualCabinetStructure.canPlace(context, state) ? state : null;
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DualCabinetBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == ModBlockEntities.DUAL_CABINET.get()
                ? (world, pos, current, entity) -> ((DualCabinetBlockEntity) entity).maintenanceTick() : null;
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved); DualCabinetStructure.confirmPlacement(level, pos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if(player.getMainHandItem().getItem() instanceof cn.piq.fcarcade.home.ZapperStandCableItem)return InteractionResult.PASS;
        return player instanceof ServerPlayer serverPlayer ? DualCabinetStructure.interact(serverPlayer, pos, hit) : InteractionResult.SUCCESS;
    }
    @Override public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        return DualCabinetStructure.breakByPlayer(level, pos, state, player, willHarvest, fluid);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        try { if (state.getBlock() != replacement.getBlock()) {
            if (level instanceof net.minecraft.server.level.ServerLevel server
                    && level.getBlockEntity(pos) instanceof DualCabinetBlockEntity cabinet){
                cn.piq.fcarcade.cabinet.ServerCabinets.removed(server, pos, cabinet.cabinetId());
                cn.piq.fcarcade.cabinet.CabinetLinks.removed(server, pos, cabinet.cabinetId());
            }
            DualCabinetStructure.removed(level, pos, state);
        } }
        finally { super.onRemove(state, level, pos, replacement, moved); }
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DualCabinetStructure.shape(state, 0);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DualCabinetStructure.collisionShape(state, 0);
    }
    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder context) { return List.of(); }
}
