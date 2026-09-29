import org.junit.jupiter.api.Test;
public final class DeviceUiTestRunner {
    public static void main(String[] names)throws Exception{
        int count=0;for(String name:names){var type=Class.forName(name);var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var instance=constructor.newInstance();
            for(var method:type.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.setAccessible(true);try{method.invoke(instance);}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(name+"."+method.getName(),e.getCause());}count++;}}
        System.out.println("{\"passed_tests\":"+count+",\"minecraft_started\":false,\"mode\":\"actual-api-and-production-layout\"}");
    }
}
