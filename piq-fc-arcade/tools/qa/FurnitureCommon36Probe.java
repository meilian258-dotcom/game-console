import cn.piq.fcarcade.furniture.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.*;
import net.minecraft.world.level.storage.loot.parameters.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Real production registries/codecs and deterministic no-world loot contexts; no player or server boot. */
public final class FurnitureCommon36Probe {
    private static int assertions;
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static void equal(double a,double b,String why){check(Math.abs(a-b)<1e-6,why);}
    private static void origin(Class<?> type,Path fc)throws Exception {check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(fc),"final source "+type.getName());}
    private static JsonObject json(ZipFile zip,String path)throws Exception {try(var input=zip.getInputStream(zip.getEntry(path))){return JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}}
    private static void ledger() {
        for(int first=0;first<2;first++)for(int turn=0;turn<4;turn++) {
            var ledger=new FurnitureLedger();var old=UUID.randomUUID();var fresh=UUID.randomUUID();
            var entry=new FurnitureLedger.Entry(old,15,64,15,turn,"oak",false,0);check(ledger.add(entry),"register entitlement");
            check(ledger.claim(old),"first removal claims item");check(!ledger.claim(old),"second callback no item");ledger.acknowledge(old,first);
            var restored=new FurnitureLedger();ledger.entries().forEach(restored::add);check(restored.get(old).closed(),"closed pair persists while other chunk unloaded");
            check(!restored.claim(old),"loaded orphan cannot duplicate");restored.add(new FurnitureLedger.Entry(fresh,15,64,15,turn,"birch",false,0));
            restored.acknowledge(old,1-first);check(restored.get(old)==null,"both removed clears old tombstone");check(restored.get(fresh)!=null&&!restored.get(fresh).closed(),"replacement untouched");
            restored.cancel(fresh);check(!restored.claim(fresh),"cancelled placement cannot drop");
        }
    }
    public static void main(String[] args)throws Exception {
        var output=System.out;var fc=Path.of(args[0]).toRealPath();
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        origin(FurnitureRegistry.class,fc);origin(FurnitureService.class,fc);origin(FurnitureSeat.class,fc);origin(FurnitureLedger.class,fc);
        var bus=BusBuilder.builder().build();FurnitureRegistry.register(bus);
        var constructor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);constructor.setAccessible(true);
        for(var registry:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE,BuiltInRegistries.ENTITY_TYPE,BuiltInRegistries.CREATIVE_MODE_TAB)) {
            ((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        }
        // Standalone registry events do not run NeoForge's final item-to-block map rebuild.
        // Use the actual vanilla BlockItem method for these real registered production items.
        for(var item:BuiltInRegistries.ITEM)if(item instanceof FurnitureBlockItem blockItem)blockItem.registerBlocks(Item.BY_BLOCK,item);
        var lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);var ops=lookup.createSerializationContext(JsonOps.INSTANCE);
        check(FurnitureRegistry.BENCHES.size()==11&&FurnitureRegistry.STOOLS.size()==11,"eleven species each");
        check(!FurnitureRegistry.SEAT.get().canSerialize()&&!FurnitureRegistry.SEAT.get().canSummon(),"seat is nonpersistent and nonsummonable");
        var contextConstructor=LootContext.class.getDeclaredConstructor(LootParams.class,RandomSource.class,HolderGetter.Provider.class);contextConstructor.setAccessible(true);
        int recipes=0,loots=0,stateRoundtrips=0,nbtRoundtrips=0;
        try(var zip=new ZipFile(fc.toFile())) {
            for(var wood:WoodSpecies.values())for(boolean bench:List.of(true,false)) {
                FurnitureBlock block=bench?FurnitureRegistry.BENCHES.get(wood).get():FurnitureRegistry.STOOLS.get(wood).get();var id=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","furniture/"+wood.id()+(bench?"_bench":"_stool"));
                check(BuiltInRegistries.BLOCK.getKey(block).equals(id),"wood block ID");check(BuiltInRegistries.ITEM.getKey(block.asItem()).equals(id),"wood item ID");
                check(block.asItem() instanceof FurnitureBlockItem,"transactional block item");check(FurnitureRegistry.FURNITURE_ENTITY.get().isValid(block.defaultBlockState()),"BE supports species");
                var stack=new ItemStack(block);if(!bench)stack.set(DataComponents.BLOCK_STATE,BlockItemStateProperties.EMPTY.with(FoldingStoolBlock.FOLDED,true));
                var restored=ItemStack.parse(lookup,stack.save(lookup)).orElseThrow();check(restored.getItem()==block.asItem()&&restored.getCount()==1,"item wood/count NBT");
                if(!bench)check(Boolean.TRUE.equals(restored.get(DataComponents.BLOCK_STATE).get(FoldingStoolBlock.FOLDED)),"folded item NBT");nbtRoundtrips++;
                var recipe=ShapedRecipe.Serializer.CODEC.codec().parse(ops,json(zip,"data/piq_fc_arcade/recipe/"+id.getPath()+".json")).getOrThrow();
                check(recipe.getResultItem(lookup).getItem()==block.asItem()&&recipe.getResultItem(lookup).getCount()==1,"real recipe output");
                boolean woodFound=false;
                for(var ingredient:recipe.getIngredients())for(var sample:ingredient.getItems())if(BuiltInRegistries.ITEM.getKey(sample.getItem()).getPath().endsWith("_planks")) {
                    woodFound=true;check(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString().equals(wood.planks()),"exact plank family");
                    for(var foreign:WoodSpecies.values())if(foreign!=wood)check(!ingredient.test(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(foreign.planks())))),"mixed wood rejected");
                }
                check(woodFound,"recipe includes actual species material");recipes++;
                if(!bench) {
                    long wool=recipe.getIngredients().stream().filter(i->i.test(new ItemStack(Items.BLUE_WOOL))).count();
                    check(wool==1,"one blue wool matches undyed fixed blue straps");
                    check(recipe.getIngredients().stream().noneMatch(i->i.test(new ItemStack(Items.WHITE_WOOL))),"white wool not silently blue");
                    check(recipe.getIngredients().stream().filter(i->i.test(new ItemStack(Items.IRON_NUGGET))).count()==2,"two fixed-metal pivots");
                }
                var loot=LootTable.DIRECT_CODEC.parse(ops,json(zip,"data/piq_fc_arcade/loot_table/blocks/"+id.getPath()+".json")).getOrThrow();
                for(var state:block.getStateDefinition().getPossibleStates()) {
                    var decoded=BlockState.CODEC.parse(ops,BlockState.CODEC.encodeStart(ops,state).getOrThrow()).getOrThrow();check(decoded==state,"real blockstate codec identity");stateRoundtrips++;
                    var be=(FurnitureBlockEntity)block.newBlockEntity(BlockPos.ZERO,state);var copy=(FurnitureBlockEntity)block.newBlockEntity(BlockPos.ZERO,state);
                    copy.loadCustomOnly(be.saveCustomOnly(lookup),lookup);check(copy.identity().equals(be.identity()),"exact BE pair UUID persistence");check(copy.onlyOpCanSetNbt(),"BE NBT operator only");
                    var shape=state.getShape(null,BlockPos.ZERO,CollisionContext.empty()).bounds();int turns=FurnitureBlock.turns(state);
                    if(bench) {
                        var mate=FurnitureService.other(BlockPos.ZERO,state);var reverse=state.cycle(WoodenBenchBlock.PART);check(FurnitureService.other(mate,reverse).equals(BlockPos.ZERO),"both half origins inverse");
                        var second=FurnitureLayout.second(turns);var seat=FurnitureLayout.seat(true,1,turns);check((int)Math.floor(seat.x())==second.x()&&(int)Math.floor(seat.z())==second.z(),"second seat within second cell");
                        equal(shape.maxY,.5,"bench real half height");equal(turns%2==0?shape.minZ:shape.minX,3.49762/16,"splayed bench leg bounds");
                    } else {
                        boolean folded=state.getValue(FoldingStoolBlock.FOLDED);equal(shape.maxY,(folded?9.4325:6.1513896)/16,"stool original height");
                        equal(turns%2==0?shape.getXsize():shape.getZsize(),9.47/16,"stool rotated width");
                    }
                    var params=new LootParams.Builder(null).withParameter(LootContextParams.ORIGIN,Vec3.ZERO).withParameter(LootContextParams.BLOCK_STATE,state).withParameter(LootContextParams.TOOL,ItemStack.EMPTY).create(LootContextParamSets.BLOCK);
                    var context=contextConstructor.newInstance(params,RandomSource.create(37),null);var drops=new ArrayList<ItemStack>();loot.getRandomItemsRaw(context,drops::add);
                    if(bench)check(drops.isEmpty(),"bench loot does not duplicate ledger drop");else {check(drops.size()==1&&drops.getFirst().getCount()==1&&drops.getFirst().getItem()==block.asItem(),"one same-wood stool drop");check(drops.getFirst().get(DataComponents.BLOCK_STATE).get(FoldingStoolBlock.FOLDED)==state.getValue(FoldingStoolBlock.FOLDED),"folded loot property preserved");}
                    loots++;
                }
            }
            // Verify compiled production boundaries, not a replacement implementation.
            for(var entry:zip.stream().filter(e->e.getName().startsWith("cn/piq/fcarcade/furniture/")&&!e.getName().contains("/client/")&&e.getName().endsWith(".class")).toList()) {
                var node=new ClassNode();new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node,0);
                for(var method:node.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)check(!call.owner.contains("/client/")&&!call.owner.startsWith("org/lwjgl/")&&!call.owner.startsWith("com/sun/jna/"),"common method avoids client/native class");
            }
            var service=new ClassNode();new ClassReader(zip.getInputStream(zip.getEntry("cn/piq/fcarcade/furniture/FurnitureService.class")).readAllBytes()).accept(service,0);
            Set<String> calls=new HashSet<>();for(var method:service.methods)for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call)calls.add(call.owner+"."+call.name);
            check(calls.contains("net/neoforged/neoforge/common/CommonHooks.fireBlockBreak"),"secondary half break protection remains");
            check(calls.contains("net/neoforged/bus/api/IEventBus.post"),"secondary half right-click protection remains");
            check(calls.contains("net/minecraft/server/level/ServerPlayer.startRiding"),"native cancellable mount event path");
            check(calls.stream().noneMatch(c->c.contains("ServerCabinets")||c.contains("HomeControllerService")||c.contains("ServerArcadeSessions")),"furniture does not change emulator lifecycle");
        }
        ledger();
        var result=new LinkedHashMap<String,Object>();result.put("ok",true);result.put("assertions",assertions);result.put("actual_registered_blocks",22);result.put("actual_registered_items",22);result.put("actual_recipes",recipes);result.put("actual_loot_contexts",loots);result.put("blockstate_roundtrips",stateRoundtrips);result.put("item_nbt_roundtrips",nbtRoundtrips);result.put("model_scale",1);result.put("game_world_started",false);result.put("limits",List.of("Loot contexts use a null world and fixed random source, not global loot modifiers or explosion gameplay.","Lifecycle entitlement and bytecode protections tested; real player events, client poses and cross-chunk world ticks require game testing."));output.println(new Gson().toJson(result));
    }
}
