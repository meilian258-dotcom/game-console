package cn.piq.pvz.registry;

import cn.piq.pvz.PvzMod;
import cn.piq.pvz.world.PvzBlock;
import cn.piq.pvz.world.PvzBlockEntity;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PvzRegistries {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PvzMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PvzMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PvzMod.MOD_ID);
    public static final DeferredBlock<PvzBlock> BOX = BLOCKS.register("player_box", () -> new PvzBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
    public static final DeferredItem<BlockItem> BOX_ITEM = ITEMS.register("player_box", () -> new BlockItem(BOX.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PvzBlockEntity>> BOX_ENTITY = ENTITIES.register("player_box", () -> new BlockEntityType<>(PvzBlockEntity::new, Set.of(BOX.get()), null));
    private PvzRegistries() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); }
}
