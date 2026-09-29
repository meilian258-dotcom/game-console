package cn.piq.fcarcade.world;

import cn.piq.fcarcade.session.ArcadeMode;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class DeluxeStreamFcArcadeBlock extends FcArcadeBlock {
    public static final MapCodec<DeluxeStreamFcArcadeBlock> CODEC =
            simpleCodec(DeluxeStreamFcArcadeBlock::new);

    public DeluxeStreamFcArcadeBlock(BlockBehaviour.Properties properties) {
        super(properties, ArcadeMode.STREAM, true);
    }

    @Override
    protected MapCodec<? extends DeluxeStreamFcArcadeBlock> codec() {
        return CODEC;
    }
}
