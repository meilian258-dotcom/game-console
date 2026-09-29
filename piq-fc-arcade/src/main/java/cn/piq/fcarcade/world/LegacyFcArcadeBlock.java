package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class LegacyFcArcadeBlock extends FcArcadeBlock
        implements EntityBlock {
    public static final MapCodec<LegacyFcArcadeBlock> CODEC =
            simpleCodec(LegacyFcArcadeBlock::new);
    private static final VoxelShape[] SELECTION_SHAPES = {
            createSelectionShape(0), createSelectionShape(1),
            createSelectionShape(2), createSelectionShape(3)
    };
    private static final VoxelShape[] COLLISION_SHAPES = {
            createCollisionShape(0), createCollisionShape(1),
            createCollisionShape(2), createCollisionShape(3)
    };

    public LegacyFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(
                properties,
                ArcadeMode.LOCKSTEP,
                ArcadeDisplayStyle.LEGACY_GENERIC);
    }

    @Override
    protected MapCodec<? extends LegacyFcArcadeBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LegacyFcArcadeBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
                            BlockState replacement, boolean movedByPiston) {
        try {
            if (level instanceof ServerLevel serverLevel
                    && state.getBlock() != replacement.getBlock()) {
                if (level.getBlockEntity(pos) instanceof LegacyFcArcadeBlockEntity cabinet){
                    cn.piq.fcarcade.cabinet.ServerCabinets.removed(serverLevel, pos, cabinet.cabinetId());
                    cn.piq.fcarcade.cabinet.CabinetLinks.removed(serverLevel, pos, cabinet.cabinetId());
                }
                ServerArcadeSessions.removeMachineDisplays(
                        serverLevel.getServer(), serverLevel.dimension(), pos);
            }
        } finally {
            // Preserve vanilla block-entity cleanup even if display removal fails.
            super.onRemove(state, level, pos, replacement, movedByPiston);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level,
                                  BlockPos pos, CollisionContext context) {
        var facing = state.getValue(FACING);
        return SELECTION_SHAPES[RocketArcadeGeometry.quarterTurns(
                facing.getStepX(), facing.getStepZ())];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                           BlockPos pos, CollisionContext context) {
        var facing = state.getValue(FACING);
        return COLLISION_SHAPES[RocketArcadeGeometry.quarterTurns(
                facing.getStepX(), facing.getStepZ())];
    }

    private static VoxelShape createSelectionShape(int quarterTurns) {
        var box = RocketArcadeGeometry.selectionBox(quarterTurns);
        // The cabinet keeps its original single anchor block/ID. A two-block
        // high outline does not make every ray through the upper air block
        // query this block; no invisible upper block is placed for interaction.
        return Shapes.box(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ());
    }

    private static VoxelShape createCollisionShape(int quarterTurns) {
        VoxelShape shape = Shapes.empty();
        for (var box : RocketArcadeGeometry.collisionBoxes(quarterTurns)) {
            shape = Shapes.or(shape, Shapes.box(box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ()));
        }
        return shape.optimize();
    }
}
