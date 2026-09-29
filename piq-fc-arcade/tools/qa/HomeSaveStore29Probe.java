package cn.piq.fcarcade.server;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
public final class HomeSaveStore29Probe {
    public static void main(String[] args)throws Exception{
        var expected=Path.of(args[0]).toRealPath();for(Class<?> c:List.of(ArcadeSaveStore.class,ServerArcadeSessions.class,PlayerSaveSlots.class))if(!Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))throw new AssertionError("Wrong origin "+c);
        int count=0;for(var method:HomeSaveStore29Test.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){var test=new HomeSaveStore29Test();test.temporary=Files.createTempDirectory(Path.of(".").toAbsolutePath().normalize(),"isolated-save-");method.setAccessible(true);try{method.invoke(test);}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(method.getName(),e.getCause());}count++;}
        System.out.println("{\"ok\":true,\"tests\":"+count+",\"actual_save_store_and_manager\":true,\"original_user_saves_accessed\":false,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false}");
    }
}
