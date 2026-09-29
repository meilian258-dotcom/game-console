package cn.piq.fcarcade.world;

import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;

public class FcArcadeBlock extends Block {
    public static final MapCodec<FcArcadeBlock> CODEC = simpleCodec(FcArcadeBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public FcArcadeBlock(BlockBehaviour.Properties properties) {
        this(properties, ArcadeMode.LOCKSTEP, ArcadeDisplayStyle.CLASSIC);
    }

    protected FcArcadeBlock(
            BlockBehaviour.Properties properties,
            ArcadeMode mode
    ) {
        this(properties, mode, ArcadeDisplayStyle.CLASSIC);
    }

    protected FcArcadeBlock(
            BlockBehaviour.Properties properties,
            ArcadeMode mode,
            boolean deluxe
    ) {
        this(
                properties,
                mode,
                deluxe ? ArcadeDisplayStyle.DELUXE
                        : ArcadeDisplayStyle.CLASSIC);
    }

    protected FcArcadeBlock(
            BlockBehaviour.Properties properties,
            ArcadeMode mode,
            ArcadeDisplayStyle displayStyle
    ) {
        super(properties);
        this.mode = mode;
        this.displayStyle = displayStyle;
        registerDefaultState(stateDefinition.any().setValue(
                FACING,
                net.minecraft.core.Direction.NORTH));
    }

    private final ArcadeMode mode;
    private final ArcadeDisplayStyle displayStyle;

    public final ArcadeMode mode() {
        return mode;
    }

    public final boolean deluxe() {
        return displayStyle == ArcadeDisplayStyle.DELUXE;
    }

    public final boolean waterFramesStyle() {
        return displayStyle.usesWaterFramesAssets();
    }

    public final ArcadeDisplayStyle displayStyle() {
        return displayStyle;
    }

    public boolean supportsFormation() {
        return displayStyle == ArcadeDisplayStyle.CLASSIC
                || displayStyle == ArcadeDisplayStyle.DELUXE;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
        BlockHitResult hitResult
    ) {
        if(player.getMainHandItem().getItem() instanceof cn.piq.fcarcade.home.ZapperStandCableItem)return InteractionResult.PASS;
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (cn.piq.fcarcade.cabinet.ServerCabinets.interact(serverPlayer, pos, hitResult))
                return InteractionResult.CONSUME;
            if (player.isShiftKeyDown()) {
                ServerArcadeSessions.openLibrary(serverPlayer, pos);
            } else {
                ServerArcadeSessions.interact(serverPlayer, pos);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
