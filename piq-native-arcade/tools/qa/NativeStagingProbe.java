package cn.piq.nativearcade.bridge;
import java.nio.file.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
/** Runs the actual JUnit assertion methods without Gradle; fixtures stay under the supplied temp root. */
public final class NativeStagingProbe {
    public static void main(String[] args)throws Exception{
        int count=0;
        for(Method m:NativeRomStagingTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){
            var fixture=new NativeRomStagingTest();fixture.temp=Files.createDirectory(Path.of(args[0]).resolve(m.getName()));
            try{m.invoke(fixture);}catch(InvocationTargetException ex){throw new AssertionError(m.getName(),ex.getCause());}
            count++;
        }
        if(count!=6)throw new AssertionError("Expected all six staging tests");
        System.out.println("JUNIT_METHODS="+count);
    }
}
