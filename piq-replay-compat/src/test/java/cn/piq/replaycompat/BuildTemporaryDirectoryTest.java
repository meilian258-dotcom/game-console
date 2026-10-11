package cn.piq.replaycompat;

import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies the actual JVM/environment defaults used by all standalone test entry points. */
public final class BuildTemporaryDirectoryTest {
    public static void main(String[] args)throws Exception {
        Path expected=Path.of(System.getProperty("piq.replay.test.temp")).toRealPath();
        if(!Path.of(System.getProperty("java.io.tmpdir")).toRealPath().equals(expected))throw new AssertionError("JVM temporary directory escaped build");
        for(String key:new String[]{"TEMP","TMP"})
            if(!Path.of(System.getenv(key)).toRealPath().equals(expected))throw new AssertionError(key+" escaped build");
        if(System.getProperty("os.name").startsWith("Windows")&&expected.getRoot().toString().equalsIgnoreCase(System.getenv().getOrDefault("SystemDrive","C:")+"\\"))
            throw new AssertionError("Windows build tests must not use the system drive");
        Path created=Files.createTempDirectory("piq-replay-build-policy-").toRealPath();
        if(!created.getParent().equals(expected))throw new AssertionError("actual temporary allocation escaped build");
        // Retain the empty fixture with this build's evidence; never clean historical system Temp.
        System.out.println("BuildTemporaryDirectoryTest: JVM/TEMP/TMP and actual temp allocation isolated in the fresh build workspace");
    }
}
