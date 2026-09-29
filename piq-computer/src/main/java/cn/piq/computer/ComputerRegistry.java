package cn.piq.computer;

import cn.piq.computer.world.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class ComputerRegistry {
    public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks(ComputerMod.ID);
    public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(ComputerMod.ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,ComputerMod.ID);
    public static final DeferredBlock<ComputerBlock> BLACK=computer("black"), WHITE=computer("white");
    public static final DeferredBlock<PeripheralBlock> KEY_BLACK=peripheral("keyboard_black",true),KEY_WHITE=peripheral("keyboard_white",true),MOUSE_BLACK=peripheral("mouse_black",false),MOUSE_WHITE=peripheral("mouse_white",false);
    public static final DeferredBlock<PeripheralBlock> KIT_BLACK=kit("black"),KIT_WHITE=kit("white");
    public static final Map<String,DeferredItem<Item>> PARTS=new LinkedHashMap<>();
    static {for(var p:Assembly.Part.values())if(p!=Assembly.Part.RAM_2)PARTS.put(p.item(),ITEMS.register(p.item(),()->new Item(new Item.Properties())));}
    public static final DeferredItem<ConnectorItem> CONNECTOR=ITEMS.register("connector",()->new ConnectorItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<ComputerEntity>> COMPUTER=ENTITIES.register("computer",()->new BlockEntityType<>(ComputerEntity::new,Set.of(BLACK.get(),WHITE.get()),null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<PeripheralEntity>> PERIPHERAL=ENTITIES.register("peripheral",()->new BlockEntityType<>(PeripheralEntity::new,Set.of(KEY_BLACK.get(),KEY_WHITE.get(),MOUSE_BLACK.get(),MOUSE_WHITE.get(),KIT_BLACK.get(),KIT_WHITE.get()),null));
    private static DeferredBlock<PeripheralBlock> kit(String color){var id="keyboard_mouse_"+color;var b=BLOCKS.register(id,()->new PeripheralBlock(true,true,BlockBehaviour.Properties.of().strength(1).noOcclusion()));ITEMS.register(id,()->new BlockItem(b.get(),new Item.Properties()));return b;}
    private static DeferredBlock<ComputerBlock> computer(String color){var b=BLOCKS.register("computer_"+color,()->new ComputerBlock(BlockBehaviour.Properties.of().strength(2).noOcclusion()));ITEMS.register("computer_"+color,()->new ComputerBlockItem(b.get(),new Item.Properties()));return b;}
    private static DeferredBlock<PeripheralBlock> peripheral(String id,boolean keyboard){var b=BLOCKS.register(id,()->new PeripheralBlock(keyboard,BlockBehaviour.Properties.of().strength(1).noOcclusion()));ITEMS.register(id,()->new BlockItem(b.get(),new Item.Properties()));return b;}
    public static Item item(Assembly.Part p){return PARTS.get(p.item()).get();}
    /** Registered for world compatibility only; no new standalone peripherals in the catalog. */
    public static boolean legacyPeripheral(String id){return Set.of("keyboard_black","keyboard_white","mouse_black","mouse_white").contains(id);}
    public static void register(IEventBus bus){BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);}
    private ComputerRegistry(){}
}
