package cn.piq.sfchome.client;
import cn.piq.sfchome.layout.SfcConsoleScale;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
public final class SfcConsoleScaleProbe {
    public static void main(String[] args)throws Exception{
        Map<String,List<SfcHardwareMeshData.Part>> raw;
        try(var reader=Files.newBufferedReader(Path.of(args[0]))){raw=SfcHardwareMeshData.read(reader);}
        var after=new TreeMap<String,List<Object>>();
        for(var group:raw.entrySet()){
            var list=new ArrayList<Object>();
            for(var part:group.getValue())list.add(Map.of("name",part.name(),"texture",part.texture(),"vertices",SfcConsoleScale.vertices(group.getKey(),part.name(),part.vertices())));
            after.put(group.getKey(),list);
        }
        System.out.println(new Gson().toJson(Map.of("before",raw,"after",after,"render_bounds",SfcConsoleScale.render(0),"body_bounds",SfcConsoleScale.body(0),"inserted_bounds",SfcConsoleScale.inserted(0),"av",SfcAvCableGeometry.console(0))));
    }
}
