package cn.piq.fcarcade.world;

import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class StreamFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<StreamFcArcadeBlock> CODEC =
            simpleCodec(StreamFcArcadeBlock::new);

    public StreamFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(properties, ArcadeMode.STREAM);
    }

    @Override
    protected MapCodec<? extends StreamFcArcadeBlock> codec() {
        return CODEC;
    }
}
