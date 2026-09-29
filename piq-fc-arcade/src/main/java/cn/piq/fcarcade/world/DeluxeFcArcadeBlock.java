package cn.piq.fcarcade.world;

import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DeluxeFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<DeluxeFcArcadeBlock> CODEC =
            simpleCodec(DeluxeFcArcadeBlock::new);

    public DeluxeFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(properties, ArcadeMode.LOCKSTEP, true);
    }

    @Override
    protected MapCodec<? extends DeluxeFcArcadeBlock> codec() {
        return CODEC;
    }
}
