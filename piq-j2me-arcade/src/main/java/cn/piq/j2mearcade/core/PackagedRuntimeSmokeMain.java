package cn.piq.j2mearcade.core;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PackagedRuntimeSmokeMain {
    private PackagedRuntimeSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        Class<?>[] runtimeClasses = {
                javax.microedition.midlet.MIDlet.class,
                javax.microedition.lcdui.Display.class,
                org.microemu.app.Common.class,
                org.microemu.app.ui.swing.SwingDeviceComponent.class
        };
        var modSource = PackagedRuntimeSmokeMain.class.getProtectionDomain().getCodeSource().getLocation();
        for (Class<?> runtimeClass : runtimeClasses) {
            var runtimeSource = runtimeClass.getProtectionDomain().getCodeSource().getLocation();
            if (!modSource.equals(runtimeSource)) {
                throw new IllegalStateException(runtimeClass.getName()
                        + " is not merged into the mod jar: " + runtimeSource);
            }
        }

        URI jarUri = URI.create("jar:" + modSource.toURI());
        try (FileSystem jar = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path jarJar = jar.getPath("/META-INF/jarjar");
            if (Files.exists(jarJar)) {
                try (var entries = Files.walk(jarJar)) {
                    if (entries.anyMatch(path -> path.getFileName().toString().startsWith("microemu-"))) {
                        throw new IllegalStateException("Nested MicroEmulator jar would recreate JPMS split packages");
                    }
                }
            }
        }
        System.out.println("PACKAGED_MICROEMU_MERGED_OK");
    }
}
