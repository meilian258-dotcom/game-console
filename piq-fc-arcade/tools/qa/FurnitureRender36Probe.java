package cn.piq.fcarcade.furniture.client;

import cn.piq.fcarcade.furniture.WoodSpecies;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.objectweb.asm.*;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Executes the final JAR loader against actual Minecraft pack precedence, without GPU or game startup. */
public final class FurnitureRender36Probe {
    private static int assertions;
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static Path write(Path root, ResourceLocation id, byte[] bytes) throws Exception {
        Path path = root.resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
        Files.createDirectories(path.getParent()); Files.write(path, bytes); return path;
    }
    private static PathPackResources pack(String name, Path root) {
        return new PathPackResources(new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty()), root);
    }
    private static MultiPackResourceManager manager(Path... roots) {
        var packs = new ArrayList<net.minecraft.server.packs.PackResources>();
        for (int i=0; i<roots.length; i++) packs.add(pack("fixture-"+i, roots[i]));
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
    }
    private static byte[] bytes(ZipFile file, String name) throws Exception {
        var entry = file.getEntry(name); check(entry != null, "Pack resource exists " + name);
        try (var in = file.getInputStream(entry)) { return in.readAllBytes(); }
    }
    private static String sprite(WoodSpecies wood) {
        String suffix = Set.of("crimson","warped").contains(wood.id()) ? "stem" : wood.id().equals("bamboo") ? "block" : "log";
        return "block/stripped_"+wood.id()+"_"+suffix;
    }
    private static void rejected(byte[] value, String label) {
        try { FurnitureResources.parse(value); throw new AssertionError("Accepted "+label); }
        catch (IllegalArgumentException | IllegalStateException expected) { assertions++; }
    }
    private static byte[] altered(byte[] good, String kind) {
        var json = JsonParser.parseString(new String(good, StandardCharsets.UTF_8)).getAsJsonObject();
        var tri = json.getAsJsonObject("groups").getAsJsonObject("wood_side").getAsJsonArray("triangles").get(0).getAsJsonObject();
        switch(kind) {
            case "version" -> json.addProperty("version", 2);
            case "uv" -> tri.getAsJsonArray("uv").get(0).getAsJsonArray().set(0, new com.google.gson.JsonPrimitive(1.1));
            case "position" -> tri.getAsJsonArray("p").get(0).getAsJsonArray().set(0, new com.google.gson.JsonPrimitive(65));
            case "normal" -> tri.add("n", JsonParser.parseString("[0,0,0]"));
            case "winding" -> { var array=tri.getAsJsonArray("p"); var first=array.get(0); array.set(0,array.get(1)); array.set(1,first); }
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String,Object> guiProjection(String json, byte[] mesh, boolean bench, boolean folded) {
        var model=BlockModel.fromString(json); var pose=new PoseStack();
        model.getTransforms().getTransform(ItemDisplayContext.GUI).apply(false,pose);
        // This is the real ItemRenderer origin adjustment followed by the final-JAR helper.
        pose.translate(-.5,-.5,-.5);FurnitureRenderer.applyItemFit(bench,folded,pose);
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(var part:FurnitureResources.parse(mesh)) {
            var data=part.vertices();
            for(int i=0;i<data.length;i+=8) {
                var point=pose.last().pose().transformPosition(new Vector3f(data[i],data[i+1],data[i+2]));
                minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minY=Math.min(minY,point.y);maxY=Math.max(maxY,point.y);
            }
        }
        check(minX>=-.5F&&maxX<=.5F&&minY>=-.5F&&maxY<=.5F,"Item remains within one GUI slot");
        check(maxX-minX>.4F&&maxY-minY>.2F,"Item is visible at meaningful inventory size");
        return Map.of("min",List.of(minX,minY),"max",List.of(maxX,maxY),"gui_pixels",List.of((maxX-minX)*16,(maxY-minY)*16));
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(SpriteContents data){super(ResourceLocation.withDefaultNamespace("test_atlas"),data,128,128,16,32);}
    }

    private static void actualSpriteApi() {
        // Allocate texture pixels only. No RenderSystem, window, texture upload or GL calls are made.
        try(var data=new SpriteContents(ResourceLocation.withDefaultNamespace("test_sprite"),new FrameSize(16,16),
                new NativeImage(16,16,true),ResourceMetadata.EMPTY)) {
            var sprite=new TestSprite(data);
            check(Math.abs(sprite.getU(0)-.125F)<.00001F&&Math.abs(sprite.getU(1)-.25F)<.00001F,"Actual sprite U uses normalized 0..1");
            check(Math.abs(sprite.getV(0)-.25F)<.00001F&&Math.abs(sprite.getV(1)-.375F)<.00001F,"Actual sprite V uses normalized 0..1");
            check(Math.abs(sprite.getU(.5F)-.1875F)<.00001F,"Actual sprite U midpoint");
        }
    }

    public static void main(String[] args) throws Exception {
        Path fc=Path.of(args[0]).toRealPath(), vanilla=Path.of(args[1]).toRealPath(), root=Path.of(args[2]);
        check(FurnitureResources.class.getProtectionDomain().getCodeSource().getLocation().toURI().equals(fc.toUri()), "Production loader from final JAR");
        check(WoodSpecies.class.getProtectionDomain().getCodeSource().getLocation().toURI().equals(fc.toUri()), "Wood definitions from final JAR");
        Files.createDirectory(root); Path base=root.resolve("base"), overlay=root.resolve("override"), broken=root.resolve("broken"), fallback=root.resolve("fallback");
        for(Path dir:List.of(base,overlay,broken,fallback))Files.createDirectory(dir);
        Map<String, byte[]> originals = new HashMap<>();
        Map<String,Object> guiBounds=new LinkedHashMap<>();
        try(var file=new ZipFile(fc.toFile()); var mc=new ZipFile(vanilla.toFile())) {
            for(var shape:FurnitureResources.Shape.values()) {
                var id=FurnitureResources.meshId(shape); byte[] data=bytes(file,"assets/"+id.getNamespace()+"/"+id.getPath());
                write(base,id,data); originals.put(shape.name(),data);
            }
            var details=FurnitureResources.textureFile(FurnitureResources.DETAILS);
            write(base,details,bytes(file,"assets/"+details.getNamespace()+"/"+details.getPath()));
            for(var wood:WoodSpecies.values()) {
                for(String suffix:List.of("","_top")) {
                    var id=FurnitureResources.textureFile(ResourceLocation.withDefaultNamespace(sprite(wood)+suffix));
                    write(base,id,bytes(mc,"assets/minecraft/"+id.getPath()));
                }
                String name="furniture/"+wood.id();
                for(String kind:List.of("bench","stool")) {
                    String stem=name+"_"+kind;
                    String itemJson=new String(bytes(file,"assets/piq_fc_arcade/models/item/"+stem+".json"),StandardCharsets.UTF_8);
                    var item=JsonParser.parseString(itemJson).getAsJsonObject();
                    check(item.get("parent").getAsString().equals("builtin/entity"),"BEWLR item "+stem);
                    check(item.get("gui_light").getAsString().equals("side"),"Normal item lighting "+stem);
                    check(item.getAsJsonObject("textures").get("particle").getAsString().equals("minecraft:"+sprite(wood)),"Original particle material "+stem);
                    for(String view:List.of("gui","ground","fixed","thirdperson_righthand","thirdperson_lefthand","firstperson_righthand","firstperson_lefthand"))
                        check(item.getAsJsonObject("display").has(view),"View transform "+stem+" "+view);
                    var block=JsonParser.parseString(new String(bytes(file,"assets/piq_fc_arcade/models/block/"+stem+".json"),StandardCharsets.UTF_8)).getAsJsonObject();
                    check(block.getAsJsonArray("elements").isEmpty(),"No duplicate baked furniture body");
                    bytes(file,"assets/piq_fc_arcade/blockstates/"+stem+".json");
                    if(wood==WoodSpecies.OAK) {
                        if(kind.equals("bench"))guiBounds.put("bench",guiProjection(itemJson,originals.get("BENCH"),true,false));
                        else {
                            guiBounds.put("open",guiProjection(itemJson,originals.get("OPEN"),false,false));
                            guiBounds.put("folded",guiProjection(itemJson,originals.get("FOLDED"),false,true));
                        }
                    }
                }
            }
            var oak=ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
            write(fallback,oak,bytes(mc,"assets/minecraft/"+oak.getPath()));
            for(var shape:FurnitureResources.Shape.values())write(fallback,FurnitureResources.meshId(shape),originals.get(shape.name()));
            // Compiled wiring inspection: no old skin loader, raw copied PNG, fullbright or NoCull path.
            var calls=new HashSet<String>(); var textureFields=new HashSet<String>();
            new ClassReader(bytes(file,"cn/piq/fcarcade/furniture/client/FurnitureRenderer.class")).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf){calls.add(owner+"."+name);}
                        @Override public void visitFieldInsn(int opcode,String owner,String name,String desc){textureFields.add(owner+"."+name);}
                    };
                }
            },0);
            check(calls.contains("net/minecraft/client/Minecraft.getTextureAtlas"),"Fresh current atlas lookup");
            check(calls.contains("net/minecraft/client/renderer/RenderType.entityCutout"),"Backface culling enabled");
            check(calls.stream().noneMatch(s->s.contains("NoCull")||s.contains("SuborHardwareMesh")||s.contains("SkinUv")),"No old loader or NoCull coupling");
            check(textureFields.contains("net/minecraft/client/renderer/texture/TextureAtlas.LOCATION_BLOCKS"),"Actual Minecraft block atlas");
            check(textureFields.stream().noneMatch(s->s.contains("FULL_BRIGHT")),"No fullbright geometry");
            check(calls.contains("com/mojang/blaze3d/vertex/VertexConsumer.setLight"),"World packed light forwarded");
        }
        actualSpriteApi();
        int triangles=0;
        var diagnostics=new ArrayList<String>();
        try(var resources=manager(base)) {
            var snapshot=FurnitureResources.load(resources,diagnostics::add);
            check(diagnostics.isEmpty(),"All final geometry loads "+diagnostics);
            check(snapshot.meshes().size()==3,"Three states loaded");
            for(var mesh:snapshot.meshes().values())for(var part:mesh){check(part.vertices().length%24==0,"Triangle data stride");triangles+=part.vertices().length/24;}
            for(var wood:WoodSpecies.values()) {
                var material=snapshot.materials().get(wood);
                check(material.side().equals(ResourceLocation.withDefaultNamespace(sprite(wood))),"Native side "+wood);
                check(material.end().equals(ResourceLocation.withDefaultNamespace(sprite(wood)+"_top")),"Native end "+wood);
                check(material.sprite(FurnitureResources.Material.CLOTH).equals(FurnitureResources.DETAILS),"Cloth fixed "+wood);
                check(material.sprite(FurnitureResources.Material.METAL).equals(FurnitureResources.DETAILS),"Metal fixed "+wood);
            }
        }
        var overrideId=ResourceLocation.withDefaultNamespace("textures/block/stripped_oak_log.png");
        BufferedImage texture=new BufferedImage(32,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++)for(int x=0;x<32;x++)texture.setRGB(x,y,((x+y)%2==0)?0xFFFF00FF:0xFF00FF00);
        var png=new ByteArrayOutputStream();ImageIO.write(texture,"PNG",png);write(overlay,overrideId,png.toByteArray());
        write(overlay,ResourceLocation.withDefaultNamespace(overrideId.getPath()+".mcmeta"),"{\"animation\":{\"frametime\":2}}".getBytes(StandardCharsets.UTF_8));
        try(var resources=manager(base,overlay)) {
            var snapshot=FurnitureResources.load(resources,diagnostics::add);
            check(snapshot.materials().get(WoodSpecies.OAK).side().toString().equals("minecraft:block/stripped_oak_log"),"Override keeps vanilla ID");
            var actual=resources.getResource(overrideId).orElseThrow();check(actual.sourcePackId().equals("fixture-1"),"Actual Minecraft pack priority");
            try(var stream=actual.open()){check(Arrays.equals(png.toByteArray(),stream.readAllBytes()),"Override pixels resolved exactly");}
            check(resources.getResource(ResourceLocation.withDefaultNamespace(overrideId.getPath()+".mcmeta")).isPresent(),"Animation metadata available to atlas");
            check(resources.getResource(FurnitureResources.textureFile(FurnitureResources.DETAILS)).orElseThrow().sourcePackId().equals("fixture-0"),"Cloth/metal unchanged under vanilla wood pack");
        }
        try(var resources=manager(base)) {
            var snapshot=FurnitureResources.load(resources,diagnostics::add);
            check(snapshot.meshes().size()==3,"Reload back to base valid");
            check(resources.getResource(overrideId).orElseThrow().sourcePackId().equals("fixture-0"),"Removed pack not retained");
        }
        write(broken,FurnitureResources.meshId(FurnitureResources.Shape.BENCH),altered(originals.get("BENCH"),"version"));
        diagnostics.clear();
        try(var resources=manager(base,broken)) {
            var snapshot=FurnitureResources.load(resources,diagnostics::add);
            check(!snapshot.meshes().containsKey(FurnitureResources.Shape.BENCH),"Corrupt pack geometry not replaced by stale prior pack");
            check(snapshot.meshes().size()==2&&diagnostics.size()==1,"Other two states survive isolated corruption");
        }
        try(var resources=manager(fallback)) {
            var snapshot=FurnitureResources.load(resources,diagnostics::add);
            check(snapshot.materials().get(WoodSpecies.OAK).side().toString().equals("minecraft:block/oak_planks"),"Same wood plank fallback");
            check(snapshot.materials().get(WoodSpecies.BIRCH).end().toString().equals("minecraft:block/oak_planks"),"Missing wood fallback is current-pack oak");
        }
        for(String kind:List.of("version","uv","position","normal","winding"))rejected(altered(originals.get("BENCH"),kind),kind);
        var result=new LinkedHashMap<String,Object>();result.put("ok",true);result.put("assertions",assertions);result.put("triangles_parsed",triangles);
        result.put("actual_multi_pack_resource_manager",true);result.put("wood_species",WoodSpecies.values().length);
        result.put("resource_override_remove_and_corrupt_mesh",true);result.put("gpu_started",false);
        result.put("actual_sprite_normalized_uv",true);result.put("actual_item_gui_projection",guiBounds);
        System.out.println(new Gson().toJson(result));
    }
}
