// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.registry;

import cn.piq.nativearcade.NativeArcadeMod;
import cn.piq.nativearcade.world.*;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class NativeArcadeRegistries {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(NativeArcadeMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(NativeArcadeMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,NativeArcadeMod.MOD_ID);
    private static BlockBehaviour.Properties cabinetProperties(){return BlockBehaviour.Properties.of().strength(2).noOcclusion().pushReaction(PushReaction.BLOCK);}
    public static final DeferredBlock<NativeCabinetBlock> CABINET=BLOCKS.register("cabinet",()->new NativeCabinetBlock(cabinetProperties()));
    public static final DeferredBlock<NativeCabinetPartBlock> CABINET_PART=BLOCKS.register("cabinet_part",()->new NativeCabinetPartBlock(cabinetProperties()));
    public static final DeferredItem<NativeCabinetBlockItem> CABINET_ITEM=ITEMS.register("cabinet",()->new NativeCabinetBlockItem(CABINET.get(),new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<NativeCabinetBlockEntity>> CABINET_ENTITY=ENTITIES.register("cabinet",()->new BlockEntityType<>(NativeCabinetBlockEntity::new,Set.of(CABINET.get()),null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<NativeCabinetPartBlockEntity>> PART_ENTITY=ENTITIES.register("cabinet_part",()->new BlockEntityType<>(NativeCabinetPartBlockEntity::new,Set.of(CABINET_PART.get()),null));
    private NativeArcadeRegistries(){}
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);}
}
