package cn.piq.sfchome.client;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import cn.piq.sfchome.client.SfcAvCableGeometry.*;

/** Actual production pure parser, animation and AV geometry; not Minecraft. */
public final class SfcUserModelProbe {
    public static void main(String[] args)throws Exception{
        var result=new LinkedHashMap<String,Object>();var controls=new LinkedHashMap<String,Object>();
        try(var r=Files.newBufferedReader(Path.of(args[0]))){for(var p:SfcHardwareMeshData.read(r).get("controller"))if(p.binding()!=null){
            var b=p.binding();var states=new LinkedHashMap<String,Object>();
            for(int mask:new int[]{0,16,32,64,128,256,512,1024,2048,1296,4095})states.put(""+mask,SfcButtonAnimation.sample(b,mask));
            controls.put(p.name(),java.util.Map.of("binding",b,"states",states));
        }}
        result.put("controls",controls);
        var tv=new Endpoint(List.of(new Vec(-3.3,.22,.875),new Vec(-3.15,.22,.875),new Vec(-3,.22,.875)),
                new Box(-3.8,0,0,-2.7,1.2,.875),new Vec(0,0,1),0,1);
        var av=SfcAvCableGeometry.build(SfcAvCableGeometry.console(0),tv);
        if(!av.visible())throw new AssertionError(av.rejection());
        if(av.quads().stream().noneMatch(q->q.part().equals("console-multi-out")))throw new AssertionError("missing MULTI OUT");
        if(av.quads().stream().anyMatch(q->q.part().startsWith("console-plug")))throw new AssertionError("RCA on console");
        if(av.quads().stream().noneMatch(q->q.part().equals("tv-plug-2")))throw new AssertionError("missing TV RCA");
        result.put("av",av);result.put("minecraft_started",false);System.out.println(new Gson().toJson(result));
    }
}
