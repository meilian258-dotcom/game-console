package cn.piq.fcarcade.core.libretro;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the launcher boundary that differs between Gradle and HMCL's IPv6-default JVM. */
class LibretroLaunch63Test {
    private static final int LOG_LIMIT = 64 * 1024;

    @Test void systemAddressPreference() throws Exception { smoke("system", false); }
    @Test void ipv6AddressPreference() throws Exception { smoke("true", false); }
    @Test void ipv4AddressPreference() throws Exception { smoke("false", false); }
    @Test void ipv4OnlyStack() throws Exception { smoke("system", true); }

    private static void smoke(String preference, boolean ipv4Only) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx128m",
                "-Djava.net.preferIPv6Addresses=" + preference,
                "-Djava.net.preferIPv4Stack=" + ipv4Only,
                "-cp", smokeClasspath(), LibretroPackagedSmoke.class.getName()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Test the explicit parent settings, not a developer/launcher injection into both JVMs.
        for (String key : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            builder.environment().remove(key);
        Process child = builder.start();
        BoundedLog log = new BoundedLog(child.getInputStream());
        Thread reader = new Thread(log, "fc63-launch-test-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = child.waitFor(30, TimeUnit.SECONDS);
            if (!finished) stopOwnedProcess(child);
            reader.join(2_000);
            String context = "parent preferIPv6Addresses=" + preference + ", preferIPv4Stack=" + ipv4Only
                    + "\n" + log.text();
            assertTrue(finished, "Core launch smoke timed out after 30 seconds: " + context);
            assertEquals(0, child.exitValue(), context);
            assertTrue(log.text().contains("30 matching post-restore frames"), context);
        } finally {
            if (child.isAlive()) stopOwnedProcess(child);
            child.getInputStream().close();
            reader.join(2_000);
        }
    }

    /** Two class roots and the actual resources, not the entire (large) Minecraft test classpath. */
    private static String smokeClasspath() throws Exception {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Class<?> type : List.of(LibretroNesCore.class, LibretroPackagedSmoke.class))
            roots.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        for (String name : List.of("/core/libretro/mesen-profile.properties", "/core/libretro/runtime.properties")) {
            URL location = LibretroNesCore.class.getResource(name);
            if (location == null) throw new IOException("Smoke test resource missing: " + name);
            if (location.getProtocol().equals("jar")) {
                roots.add(Path.of(((JarURLConnection) location.openConnection()).getJarFileURL().toURI()));
            } else if (location.getProtocol().equals("file")) {
                Path root = Path.of(location.toURI());
                for (String ignored : name.substring(1).split("/")) root = root.getParent();
                roots.add(root);
            } else throw new IOException("Unsupported smoke test resource protocol: " + location.getProtocol());
        }
        return roots.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    /** Only processes descended from this test's explicitly created JVM are eligible for cleanup. */
    private static void stopOwnedProcess(Process child) throws InterruptedException {
        List<ProcessHandle> descendants = child.descendants().toList();
        child.destroy();
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(2, TimeUnit.SECONDS);
        }
        for (ProcessHandle descendant : descendants) if (descendant.isAlive()) descendant.destroy();
        for (ProcessHandle descendant : descendants) if (descendant.isAlive()) descendant.destroyForcibly();
    }

    /** Continue draining after the cap; a noisy worker cannot deadlock or grow the test heap. */
    private static final class BoundedLog implements Runnable {
        private final InputStream source;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;

        private BoundedLog(InputStream source) { this.source = source; }

        @Override public void run() {
            try {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = source.read(buffer)) >= 0) append(buffer, count);
            } catch (IOException ignored) { /* Closing an owned timed-out process also closes its pipe. */ }
        }

        private synchronized void append(byte[] buffer, int count) {
            int retained = Math.min(count, LOG_LIMIT - bytes.size());
            bytes.write(buffer, 0, retained);
            if (retained != count) truncated = true;
        }

        private synchronized String text() {
            return bytes.toString(StandardCharsets.UTF_8) + (truncated ? "\n[test log truncated]" : "");
        }
    }
}
