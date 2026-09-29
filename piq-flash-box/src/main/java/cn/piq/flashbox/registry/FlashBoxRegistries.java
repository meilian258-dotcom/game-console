package cn.piq.flashbox.registry;

import cn.piq.flashbox.FlashBoxMod;
import cn.piq.flashbox.world.FlashBoxBlock;
import cn.piq.flashbox.world.FlashBoxBlockEntity;
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

public final class FlashBoxRegistries {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FlashBoxMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FlashBoxMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FlashBoxMod.MOD_ID);
    public static final DeferredBlock<FlashBoxBlock> BOX = BLOCKS.register("player_box", () -> new FlashBoxBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
    public static final DeferredItem<BlockItem> BOX_ITEM = ITEMS.register("player_box", () -> new BlockItem(BOX.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlashBoxBlockEntity>> BOX_ENTITY = ENTITIES.register("player_box", () -> new BlockEntityType<>(FlashBoxBlockEntity::new, Set.of(BOX.get()), null));
    private FlashBoxRegistries() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); }
}
