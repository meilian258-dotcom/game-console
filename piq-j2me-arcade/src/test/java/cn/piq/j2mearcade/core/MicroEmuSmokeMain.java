package cn.piq.j2mearcade.core;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Java 21 compatibility probe that does not depend on NeoForge's JUnit runner. */
public final class MicroEmuSmokeMain {
    private MicroEmuSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        require("org.microemu.MicroEmulator");
        require("org.microemu.device.Device");
        Class<?> deviceClass = require("org.microemu.device.j2se.J2SEDevice");
        Class<?> loaderClass = require("org.microemu.app.classloader.MIDletClassLoader");
        require("org.microemu.util.MemoryRecordStoreManager");

        Constructor<?>[] constructors = deviceClass.getDeclaredConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("J2SEDevice exposes no constructor");
        }

        Object device = deviceClass.getConstructor().newInstance();
        Object midletLoader = loaderClass.getConstructor(ClassLoader.class)
                .newInstance(MicroEmuSmokeMain.class.getClassLoader());

        verifyGameJarMetadataReader();

        System.out.println("MICROEMU_SMOKE_OK Java=" + System.getProperty("java.version")
                + " device=" + device.getClass().getSimpleName()
                + " loader=" + midletLoader.getClass().getSimpleName());
    }

    private static void verifyGameJarMetadataReader() throws Exception {
        Path directory = Files.createTempDirectory("piq-j2me-smoke-");
        try {
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue("MIDlet-Name", "PIQ Probe Game");
            attributes.putValue("MIDlet-Vendor", "PIQ");
            attributes.putValue("MIDlet-Version", "1.0");
            attributes.putValue("MIDlet-1", "Probe, /icon.png, probe.Main");

            Path jar = directory.resolve("probe.jar");
            try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                // A manifest-only jar is sufficient for the metadata boundary test.
            }

            J2meGameDescriptor descriptor = J2meGameLibrary.read(jar);
            if (!"PIQ Probe Game".equals(descriptor.name())
                    || !"probe.Main".equals(descriptor.midletEntry())) {
                throw new IllegalStateException("Java ME manifest parsing returned unexpected data: " + descriptor);
            }
            if (J2meGameLibrary.scan(directory).size() != 1) {
                throw new IllegalStateException("Java ME game directory scan did not return the probe jar");
            }
            System.out.println("J2ME_JAR_SCAN_OK " + descriptor.name() + " -> " + descriptor.midletEntry());
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // The process temp directory is harmless if antivirus holds a file briefly.
                    }
                });
            }
        }
    }

    private static Class<?> require(String name) throws ClassNotFoundException {
        Class<?> type = Class.forName(name, false, MicroEmuSmokeMain.class.getClassLoader());
        System.out.println("loaded " + type.getName() + " from "
                + type.getProtectionDomain().getCodeSource().getLocation());
        return type;
    }
}
