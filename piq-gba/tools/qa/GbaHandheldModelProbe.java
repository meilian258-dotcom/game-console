package cn.piq.gba.client;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.core.Direction;

/** Actual Minecraft Java-model parser; no Minecraft instance, native core or OpenGL. */
public final class GbaHandheldModelProbe {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        String[] parts={"body","screen","dpad","button_a","button_b","button_select","button_start","shoulder_l","shoulder_r"};
        int[] counts={533,1,5,16,16,9,9,73,73};
        int elements=0,faces=0,rotations=0;
        Path input=Path.of(args[0]);boolean jar=Files.isRegularFile(input);
        try(ZipFile archive=jar?new ZipFile(input.toFile()):null){
            for(int i=0;i<parts.length;i++){
                String name="assets/piq_gba/models/item/handheld/"+parts[i]+".json";
                String text=jar?new String(archive.getInputStream(archive.getEntry(name)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8):Files.readString(input.resolve(name));
                var model=BlockModel.fromString(text);check(model.getElements().size()==counts[i],"Element count "+parts[i]);
                for(var element:model.getElements()){
                    elements++;faces+=element.faces.size();if(element.rotation!=null)rotations++;
                    check(element.from.isFinite()&&element.to.isFinite(),"Finite actual parsed positions");
                    for(var face:element.faces.values()){
                        check(face.texture().equals("#0"),"Only pinned user texture");
                        for(float uv:face.uv().uvs)check(Float.isFinite(uv)&&uv>=0&&uv<=16,"Original normalized UV range");
                    }
                    if(parts[i].equals("screen")){
                        check(element.rotation==null&&element.faces.size()==1&&element.faces.containsKey(Direction.UP),"Exact original screen face");
                        check(Math.abs((element.to.x-element.from.x)/(element.to.z-element.from.z)-1.5)<1e-6,"Actual MC screen 3:2");
                        check(element.from.y<element.to.y&&element.to.y<GbaHandheldLayout.SCREEN_Y,"Live quad only epsilon above static screen");
                    }
                }
            }
        }
        check(elements==735&&faces==4370&&rotations==451,"Complete original inventory");
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"elements\":"+elements+",\"faces\":"+faces+",\"rotations\":"+rotations+",\"actual_minecraft_parser\":true}");
    }
}
