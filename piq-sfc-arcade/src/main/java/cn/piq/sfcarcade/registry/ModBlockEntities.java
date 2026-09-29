// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.registry;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.world.SfcArcadeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SfcArcadeMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SfcArcadeBlockEntity>>
            SFC_ARCADE = BLOCK_ENTITIES.register(
            "sfc_arcade",
            () -> new BlockEntityType<>(
                    SfcArcadeBlockEntity::new,
                    Set.of(ModBlocks.SFC_ARCADE.get()),
                    null));

    private ModBlockEntities() {
    }
}
