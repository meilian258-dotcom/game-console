package cn.piq.fcarcade.furniture;

import cn.piq.fcarcade.FcArcadeMod;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

/** Isolated optional furniture content inside FC; no arcade sessions or keyboard handlers. */
public final class FurnitureRegistry {
    private static final DeferredRegister<Block> BLOCKS=DeferredRegister.create(Registries.BLOCK,FcArcadeMod.MOD_ID);
    private static final DeferredRegister<Item> ITEMS=DeferredRegister.create(Registries.ITEM,FcArcadeMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES=DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,FcArcadeMod.MOD_ID);
    private static final DeferredRegister<EntityType<?>> SEATS=DeferredRegister.create(Registries.ENTITY_TYPE,FcArcadeMod.MOD_ID);
    public static final Map<WoodSpecies,DeferredHolder<Block,WoodenBenchBlock>> BENCHES=new LinkedHashMap<>();
    public static final Map<WoodSpecies,DeferredHolder<Block,FoldingStoolBlock>> STOOLS=new LinkedHashMap<>();
    static {
        for(var wood:WoodSpecies.values()) {
            var bench=BLOCKS.register("furniture/"+wood.id()+"_bench",()->new WoodenBenchBlock(wood,properties()));
            var stool=BLOCKS.register("furniture/"+wood.id()+"_stool",()->new FoldingStoolBlock(wood,properties()));
            BENCHES.put(wood,bench);STOOLS.put(wood,stool);
            ITEMS.register("furniture/"+wood.id()+"_bench",()->new FurnitureBlockItem(bench.get(),new Item.Properties()));
            ITEMS.register("furniture/"+wood.id()+"_stool",()->new FurnitureBlockItem(stool.get(),new Item.Properties()));
        }
    }
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<FurnitureBlockEntity>> FURNITURE_ENTITY=ENTITIES.register("furniture/furniture",
        ()->BlockEntityType.Builder.of(FurnitureBlockEntity::new,BLOCKS.getEntries().stream().map(DeferredHolder::get).toArray(Block[]::new)).build(null));
    public static final DeferredHolder<EntityType<?>,EntityType<FurnitureSeat>> SEAT=SEATS.register("furniture/seat",()->EntityType.Builder.<FurnitureSeat>of(FurnitureSeat::new,MobCategory.MISC)
        .sized(.01F,.01F).clientTrackingRange(8).updateInterval(10).noSave().noSummon().fireImmune().build(FcArcadeMod.MOD_ID+":furniture/seat"));
    /** Append furniture to the existing FC page without registering another creative tab. */
    public static void addCreativeItems(CreativeModeTab.Output output) {
        for(var wood:WoodSpecies.values()) {
            output.accept(BENCHES.get(wood).get());
            output.accept(STOOLS.get(wood).get());
        }
    }
    private static BlockBehaviour.Properties properties() { return BlockBehaviour.Properties.of().strength(2.0F,3.0F).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK); }
    public static void register(IEventBus bus) { BLOCKS.register(bus);ITEMS.register(bus);ENTITIES.register(bus);SEATS.register(bus); }
    private FurnitureRegistry() {}
}
