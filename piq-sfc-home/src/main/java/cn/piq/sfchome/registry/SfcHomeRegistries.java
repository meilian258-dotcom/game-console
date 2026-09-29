package cn.piq.sfchome.registry;
import cn.piq.sfchome.item.*;
import cn.piq.sfchome.world.*;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
public final class SfcHomeRegistries {
    private static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks("piq_sfc_home");
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems("piq_sfc_home");
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,"piq_sfc_home");
    public static final DeferredBlock<SfcHomeConsoleBlock> CONSOLE=BLOCKS.register("console",()->new SfcHomeConsoleBlock(BlockBehaviour.Properties.of().strength(1.5F).noOcclusion()));
    public static final DeferredItem<BlockItem> CONSOLE_ITEM=ITEMS.register("console",()->new BlockItem(CONSOLE.get(),new Item.Properties()));
    public static final DeferredItem<SfcCartridgeItem> CARTRIDGE=ITEMS.register("cartridge",()->new SfcCartridgeItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<SfcControllerItem> CONTROLLER=ITEMS.register("controller",()->new SfcControllerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<SfcHomeConsoleBlockEntity>> CONSOLE_ENTITY=ENTITIES.register("console",()->new BlockEntityType<>(SfcHomeConsoleBlockEntity::new,Set.of(CONSOLE.get()),null));
    private SfcHomeRegistries(){}
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);}
}
