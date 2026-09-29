package cn.piq.j2mearcade.registry;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;
import cn.piq.j2mearcade.world.J2meArcadeBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, PiqJ2meArcadeMod.MOD_ID);

    public static final DeferredHolder<Block, J2meArcadeBlock> J2ME_ARCADE =
            BLOCKS.register("j2me_arcade", () -> new J2meArcadeBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    private ModBlocks() {
    }
}
