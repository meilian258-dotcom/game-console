import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;

public final class ControllerCapture30TestRunner {
    public static void main(String[] args)throws Exception {
        int tests=0, origins=0;
        if(args.length>0)for(String name:new String[]{"cn.piq.fcarcade.client.ControllerCapturePolicy","cn.piq.fcarcade.client.ControllerCapturePolicy$Held","cn.piq.fcarcade.client.HomeInputSequences","cn.piq.fcarcade.home.HomeRuntimeAuthority","cn.piq.fcarcade.session.LockstepState"}){
            var type=Class.forName(name);
            if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()))throw new AssertionError("Unexpected production origin: "+name);
            origins++;
        }
        for(String name:new String[]{"cn.piq.fcarcade.client.ControllerCapturePolicyTest","cn.piq.fcarcade.client.HomeInputSequencesTest","cn.piq.fcarcade.home.HomeGunKeyboardFallbackTest","cn.piq.fcarcade.home.HomeGunController29Test"}){
            var type=Class.forName(name);var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
            for(var method:type.getDeclaredMethods())if(method.isAnnotationPresent(org.junit.jupiter.api.Test.class)){
                method.setAccessible(true);try{method.invoke(ctor.newInstance());tests++;}catch(InvocationTargetException failure){throw new AssertionError(name+"."+method.getName(),failure.getCause());}
            }
        }
        System.out.println("{\"ok\":true,\"tests\":"+tests+",\"origin_assertions\":"+origins+",\"minecraft_or_core_started\":false}");
    }
}
