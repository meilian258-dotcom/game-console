package cn.piq.sfchome.client;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Refuses a classes directory: every tested production class must originate in the final JAR. */
public final class SfcUserModelOriginProbe {
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();var origins=new LinkedHashMap<String,String>();
        for(String name:new String[]{"SfcHardwareMeshData","SfcControllerPoseLayout","SfcButtonAnimation","SfcCoverGeometry","SfcAvCableGeometry"}){
            Class<?> type=Class.forName("cn.piq.sfchome.client."+name);
            Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if(!actual.equals(expected))throw new AssertionError("Not final JAR: "+type+" from "+actual);
            origins.put(name,actual.toString());
        }
        System.out.println(new Gson().toJson(java.util.Map.of("ok",true,"production_origin","final-jar-only","production_classes",origins,"minecraft_or_core_started",false)));
    }
}
