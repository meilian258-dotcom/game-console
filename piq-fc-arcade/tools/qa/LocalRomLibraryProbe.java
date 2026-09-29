package cn.piq.fcarcade.client.rom;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/** Executes the real JUnit methods against controlled temp folders without loading Minecraft. */
public final class LocalRomLibraryProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); int passed=0, skipped=0;
        var methods=LocalRomLibraryTest.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(java.lang.reflect.Method::getName));
        for (var method:methods) if(method.isAnnotationPresent(Test.class)) {
            var test=new LocalRomLibraryTest(); test.temp=Files.createDirectory(root.resolve(method.getName()));
            try { method.invoke(test); passed++; }
            catch(InvocationTargetException error) {
                if(error.getCause() instanceof TestAbortedException) { skipped++; System.out.println("SKIP="+method.getName()); }
                else throw new AssertionError(method.getName(),error.getCause());
            }
        }
        if(passed+skipped!=13) throw new AssertionError("Unexpected test count");
        System.out.println("LOCAL_ROM_LIBRARY_JUNIT=13 PASSED="+passed+" SKIPPED="+skipped);
    }
}
