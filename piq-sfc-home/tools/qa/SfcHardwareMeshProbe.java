package cn.piq.sfchome.client;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Runs the real parser/pose classes without Minecraft or a rendering context. */
public final class SfcHardwareMeshProbe {
    public static void main(String[] args)throws Exception{
        var result=new LinkedHashMap<String,Object>();
        try(var reader=Files.newBufferedReader(Path.of(args[0]))){
            var groups=SfcHardwareMeshData.read(reader);var counts=new LinkedHashMap<String,Object>();
            for(var entry:groups.entrySet())counts.put(entry.getKey(),List.of(entry.getValue().size(),entry.getValue().stream().mapToInt(p->p.vertices().length/24).sum()));
            result.put("groups",counts);
        }
        var rigs=new LinkedHashMap<String,Object>();
        for(double swing:new double[]{0,.05,.125,.25,.5,.75,1}){
            var p=SfcControllerPoseLayout.rig(0,swing);rigs.put(Double.toString(swing),List.of(p.y(),p.z(),p.pitch()));
        }
        result.put("rigs",rigs);result.put("controller_scale",SfcControllerPoseLayout.CONTROLLER_SCALE);
        var arms=new LinkedHashMap<String,Object>();
        for(boolean right:new boolean[]{false,true}){var p=SfcControllerPoseLayout.arm(right);arms.put(right?"right":"left",List.of(p.x(),p.y(),p.z(),p.pitch(),p.roll(),p.scale()));}
        result.put("arms",arms);result.put("minecraft_started",false);
        System.out.println(new Gson().toJson(result));
    }
}
