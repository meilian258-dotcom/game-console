package cn.piq.fcarcade.world;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class WaterFramesTvBoxFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<WaterFramesTvBoxFcArcadeBlock> CODEC =
            simpleCodec(WaterFramesTvBoxFcArcadeBlock::new);

    public WaterFramesTvBoxFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(
                properties,
                ArcadeMode.LOCKSTEP,
                ArcadeDisplayStyle.WATERFRAMES_TV_BOX);
    }

    @Override
    protected MapCodec<? extends WaterFramesTvBoxFcArcadeBlock> codec() {
        return CODEC;
    }
}
