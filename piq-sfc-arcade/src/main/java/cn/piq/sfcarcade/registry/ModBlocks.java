// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.registry;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.world.SfcArcadeBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, SfcArcadeMod.MOD_ID);

    public static final DeferredHolder<Block, SfcArcadeBlock> SFC_ARCADE =
            BLOCKS.register("sfc_arcade", () -> new SfcArcadeBlock(
                    BlockBehaviour.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    private ModBlocks() {
    }
}
