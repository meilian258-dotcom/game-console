package cn.piq.j2mearcade.registry;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;
import cn.piq.j2mearcade.world.J2meArcadeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PiqJ2meArcadeMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<J2meArcadeBlockEntity>>
            J2ME_ARCADE = BLOCK_ENTITIES.register("j2me_arcade", () -> new BlockEntityType<>(
                    J2meArcadeBlockEntity::new,
                    Set.of(ModBlocks.J2ME_ARCADE.get()),
                    null));

    private ModBlockEntities() {
    }
}
