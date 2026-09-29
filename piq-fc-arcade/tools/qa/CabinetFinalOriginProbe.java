package cn.piq.fcarcade.cabinet;
import java.nio.file.Path;
/** Loads without initializing production entry points; every named type must come from the exact final JAR. */
public final class CabinetFinalOriginProbe {
    public static void main(String[] args)throws Exception{
        if(args.length<2)throw new AssertionError("Expected final jar and production types");
        Path expected=Path.of(args[0]).toRealPath();int count=0;
        for(int i=1;i<args.length;i++){
            Class<?> type=Class.forName(args[i],false,CabinetFinalOriginProbe.class.getClassLoader());
            Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if(!actual.equals(expected))throw new AssertionError("Wrong production origin: "+type.getName()+" "+actual);
            count++;
        }
        System.out.println("{\"ok\":true,\"checked_production_types\":"+count+",\"production_origin\":\"final-jar-only\",\"production_compiled\":false}");
    }
}
