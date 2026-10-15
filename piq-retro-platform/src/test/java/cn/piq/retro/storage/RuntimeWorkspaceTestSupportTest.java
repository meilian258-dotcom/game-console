package cn.piq.retro.storage;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeWorkspaceTestSupportTest {
    @Test void runtimeInstanceUsesTheConfiguredTestTemporaryRoot() throws Exception {
        Path instance = RuntimeWorkspaceTestSupport.configure();
        assertTrue(instance.startsWith(RuntimeWorkspaceTestSupport.temporaryInstanceRoot()));
        assertEquals(instance, RuntimeWorkspaceTestSupport.configure());
        try (var workspace = RuntimeWorkspace.create("libretro", 0)) {
            assertTrue(workspace.directory().startsWith(instance.resolve("game-console/runtime-sessions")));
        }
    }

    @Test void childIsHeapBoundedAndInheritsOnlyExplicitTemporarySettings() {
        var child = RuntimeWorkspaceTestSupport.child(64, "test-classpath", RuntimeWorkspaceTestSupportTest.class, "fixture");
        var args = child.command();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        assertTrue(args.contains("-Xms16m")); assertTrue(args.contains("-Xmx64m"));
        assertTrue(args.contains("-Djava.io.tmpdir=" + temporary));
        assertTrue(args.contains("-Djna.tmpdir=" + temporary));
        assertTrue(args.contains("-D" + RuntimeWorkspaceTestSupport.ROOT_PROPERTY + "=" + RuntimeWorkspaceTestSupport.temporaryInstanceRoot()));
        assertTrue(args.contains("-Dwasmtime4j.native.tmpdir=" + temporary));
        assertEquals("fixture", args.getLast());
        assertEquals(temporary.toString(), child.environment().get("TEMP"));
        assertEquals(temporary.toString(), child.environment().get("TMP"));
        for (String key : new String[]{"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"})
            assertFalse(child.environment().containsKey(key));
    }

    @Test void childHeapLimitCannotBeUnboundedOrOversized() {
        for (int size : new int[]{0, 31, 513, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimeWorkspaceTestSupport.child(size, "cp", RuntimeWorkspaceTestSupportTest.class));
    }
}
