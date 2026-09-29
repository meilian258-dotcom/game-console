import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
public final class Home29PureProbe {
    public static final List<String> TESTS=List.of("HomeRuntimeAuthorityTest","HomeGunController29Test","HomeIdleController29Test","HomeSaveIntent29Test","HomeControllerLedgerTest","HomeControllerInventoryTest","HomeControllerRecyclingTest");
    public static void main(String[] args)throws Exception{
        int count=0;for(var name:TESTS){var t=Class.forName("cn.piq.fcarcade.home."+name);var ctor=t.getDeclaredConstructor();ctor.setAccessible(true);for(var m:t.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.setAccessible(true);try{m.invoke(ctor.newInstance());}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(name+"."+m.getName(),e.getCause());}count++;}}
        if(args.length>0){var expected=Path.of(args[0]).toRealPath();for(String n:List.of("HomeRuntimeAuthority","HomeControllerLedger","HomeControllerInventory","HomeSaveIntent")){var t=Class.forName("cn.piq.fcarcade.home."+n);if(!Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))throw new AssertionError("Not final class: "+n);}}
        System.out.println("{\"ok\":true,\"tests\":"+count+",\"production_origin\":\""+(args.length>0?"final-jar-only":"source-pure")+"\",\"minecraft_or_native_core_started\":false}");
    }
}
