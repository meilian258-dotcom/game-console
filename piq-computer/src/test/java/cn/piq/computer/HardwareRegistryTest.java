package cn.piq.computer;

import cn.piq.computer.world.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Minecraft registries, item/NBT/recipe codecs and shapes; no running world or GPU. */
class HardwareRegistryTest {
    static RegistryAccess lookup;
    @BeforeAll static void bootstrap() throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var bus=BusBuilder.builder().build();ComputerRegistry.register(bus);
        var ctor=RegisterEvent.class.getDeclaredConstructor(ResourceKey.class,Registry.class);ctor.setAccessible(true);
        for(var r:List.of(BuiltInRegistries.BLOCK,BuiltInRegistries.ITEM,BuiltInRegistries.BLOCK_ENTITY_TYPE)){
            ((MappedRegistry<?>)r).unfreeze();bus.post(ctor.newInstance(r.key(),r));r.freeze();
        }
        for(var entry:ComputerRegistry.ITEMS.getEntries())if(entry.get() instanceof BlockItem item)item.registerBlocks(Item.BY_BLOCK,item);
        lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
    @Test void realBlockStatesAndNbt(){
        for(var holder:ComputerRegistry.BLOCKS.getEntries())for(var state:holder.get().getStateDefinition().getPossibleStates()){
            var ops=lookup.createSerializationContext(JsonOps.INSTANCE);
            assertSame(state,BlockState.CODEC.parse(ops,BlockState.CODEC.encodeStart(ops,state).getOrThrow()).getOrThrow());
            var b=holder.get();var voxel=state.getShape(null,BlockPos.ZERO,CollisionContext.empty());
            if(b instanceof ComputerBlock pc&&state.getValue(ComputerBlock.HALF)==DoubleBlockHalf.UPPER){assertNull(pc.newBlockEntity(BlockPos.ZERO,state));assertTrue(voxel.isEmpty());continue;}
            var shape=voxel.bounds();assertTrue(shape.getXsize()>0&&shape.getYsize()>0&&shape.getZsize()>0);
            if(b instanceof ComputerBlock pc){
                assertEquals(.825,shape.maxY,1e-12);assertTrue(shape.minX>=0&&shape.maxX<=1&&shape.minZ>=0&&shape.maxZ<=1);
                var one=(ComputerEntity)pc.newBlockEntity(BlockPos.ZERO,state);one.installed=255;one.panelOpen=true;one.typed="hello";one.keyboard=new BlockPos(1,2,3);one.keyboardId=UUID.randomUUID();
                var two=(ComputerEntity)pc.newBlockEntity(BlockPos.ZERO,state);two.loadCustomOnly(one.saveCustomOnly(lookup),lookup);
                assertEquals(255,two.installed);assertTrue(two.panelOpen);assertEquals(one.hardwareId(),two.hardwareId());assertEquals(one.keyboardId,two.keyboardId);assertEquals(one.keyboard,two.keyboard);assertNull(two.lease.owner());assertTrue(two.onlyOpCanSetNbt());
            } else if(b instanceof PeripheralBlock peripheral){
                var one=(PeripheralEntity)peripheral.newBlockEntity(BlockPos.ZERO,state);one.computer=new BlockPos(12,30,8);one.computerId=UUID.randomUUID();
                var two=(PeripheralEntity)peripheral.newBlockEntity(BlockPos.ZERO,state);two.loadCustomOnly(one.saveCustomOnly(lookup),lookup);assertEquals(one.id,two.id);assertEquals(one.computerId,two.computerId);assertEquals(one.computer,two.computer);assertTrue(two.onlyOpCanSetNbt());
            }
        }
    }
    @Test void realRecipes()throws Exception{
        int count=0;try(var paths=Files.list(Path.of("src/main/resources/data/piq_computer/recipe"))){for(var p:paths.toList()){
            var recipe=new ShapelessRecipe.Serializer().codec().codec().parse(lookup.createSerializationContext(JsonOps.INSTANCE),JsonParser.parseString(Files.readString(p))).getOrThrow();
            var item=recipe.getResultItem(lookup);assertFalse(item.isEmpty());assertEquals("piq_computer",BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace());assertEquals(1,item.getCount());assertTrue(recipe.getIngredients().size()<=9);count++;
        }}assertEquals(14,count);
    }
    @Test void oppositeRotationsRecoverPowerPoint(){
        var original=new Vec3(10.75/16,14.3/16,.63/16);
        assertTrue(original.distanceTo(ComputerBlock.local(original,Direction.NORTH))<1e-12);
        assertTrue(original.distanceTo(ComputerBlock.local(new Vec3(1-original.z,original.y,original.x),Direction.EAST))<1e-12);
        assertTrue(original.distanceTo(ComputerBlock.local(new Vec3(1-original.x,original.y,1-original.z),Direction.SOUTH))<1e-12);
        assertTrue(original.distanceTo(ComputerBlock.local(new Vec3(original.z,original.y,1-original.x),Direction.WEST))<1e-12);
    }
    @Test void actualMinecraftModelParser()throws Exception{
        try(var files=Files.walk(Path.of("src/main/resources/assets/piq_computer/models"))){for(var p:files.filter(f->f.toString().endsWith(".json")).toList())assertNotNull(net.minecraft.client.renderer.block.model.BlockModel.fromString(Files.readString(p)),p.toString());}
    }
    @Test void firmwareRasterPreview()throws Exception{
        var state=ComputerRegistry.BLACK.get().defaultBlockState();var pc=new ComputerEntity(BlockPos.ZERO,state);pc.installed=255;pc.powered=true;pc.typed="Keyboard & mouse test";pc.lastKey=65;pc.pointerX=480;pc.pointerY=260;
        var method=cn.piq.computer.client.FirmwareDisplay.class.getDeclaredMethod("draw",ComputerEntity.class);method.setAccessible(true);
        var image=(java.awt.image.BufferedImage)method.invoke(null,pc);assertEquals(640,image.getWidth());assertEquals(480,image.getHeight());assertNotEquals(image.getRGB(480,260),image.getRGB(479,259));
        assertTrue(javax.imageio.ImageIO.write(image,"png",Path.of("build/firmware-preview.png").toFile()));
    }
}
