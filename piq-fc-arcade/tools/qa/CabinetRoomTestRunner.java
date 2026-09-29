import org.junit.jupiter.api.Test;
/** Uses JUnit's default per-method instance lifecycle, without booting Minecraft. */
public final class CabinetRoomTestRunner {
    public static void main(String[] names)throws Exception{
        int count=0;
        for(String name:names){var type=Class.forName(name);var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
            for(var method:type.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){
                var instance=constructor.newInstance();method.setAccessible(true);
                try{method.invoke(instance);}catch(java.lang.reflect.InvocationTargetException error){throw new AssertionError(name+"."+method.getName(),error.getCause());}count++;
            }
        }
        System.out.println("{\"passed_tests\":"+count+",\"minecraft_started\":false,\"mode\":\"actual-production-pure-state-machines\"}");
    }
}
