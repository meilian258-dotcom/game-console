package cn.piq.retro.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only instance ownership and bounded child JVMs. Never changes game defaults. */
public final class RuntimeWorkspaceTestSupport {
    public static final String ROOT_PROPERTY = "piq.test.runtimeRoot";
    private static Path instance;

    private RuntimeWorkspaceTestSupport() {}

    public static synchronized Path configure() throws IOException {
        if (instance == null) {
            Path root = temporaryInstanceRoot();
            // The unmodified Windows native bridge also calls non-extended Win32
            // path APIs. Leave room for the owned session UUID and DLL filename.
            if (System.getProperty("os.name").startsWith("Windows") && root.toString().length() > 128)
                throw new IOException("Test runtime root is too long; use a shorter -PpiqBuildWorkDir or -D" + ROOT_PROPERTY);
            Files.createDirectories(root);
            instance = Files.createTempDirectory(root, "r-");
        }
        RuntimeWorkspace.configure(instance);
        return instance;
    }

    public static Path temporaryInstanceRoot() {
        return Path.of(System.getProperty(ROOT_PROPERTY, System.getProperty("java.io.tmpdir")))
                .toAbsolutePath().normalize();
    }

    public static ProcessBuilder child(int heapMiB, String classpath, Class<?> main, String... arguments) {
        if (heapMiB < 32 || heapMiB > 512) throw new IllegalArgumentException("Test child heap limit");
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xms16m", "-Xmx" + heapMiB + "m",
                "-Djava.io.tmpdir=" + temporary, "-Djna.tmpdir=" + temporary,
                "-D" + ROOT_PROPERTY + "=" + temporaryInstanceRoot(),
                "-Dwasmtime4j.native.tmpdir=" + temporary,
                "-XX:ErrorFile=" + temporary.resolve("test-child-hs_err_pid%p.log"),
                "-XX:HeapDumpPath=" + temporary, "-cp", classpath, main.getName()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            builder.environment().remove(key);
        builder.environment().put("TEMP", temporary.toString());
        builder.environment().put("TMP", temporary.toString());
        builder.environment().put("TMPDIR", temporary.toString());
        return builder;
    }
}
