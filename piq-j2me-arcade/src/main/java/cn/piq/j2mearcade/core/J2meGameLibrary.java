package cn.piq.j2mearcade.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Reads metadata only. User game jars are never loaded into Minecraft's classloader here. */
public final class J2meGameLibrary {
    public static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;

    private J2meGameLibrary() {
    }

    public static List<J2meGameDescriptor> scan(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(J2meGameLibrary::isJar)
                    .filter(J2meGameLibrary::withinSizeLimit)
                    .map(J2meGameLibrary::tryRead)
                    .flatMap(java.util.Optional::stream)
                    .sorted(Comparator.comparing(J2meGameDescriptor::name,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public static J2meGameDescriptor read(Path jar) throws IOException {
        Path normalized = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !isJar(normalized)) {
            throw new IOException("Not a Java ME jar: " + normalized);
        }
        if (!withinSizeLimit(normalized)) {
            throw new IOException("Java ME jar exceeds 64 MiB: " + normalized);
        }

        try (JarFile archive = new JarFile(normalized.toFile(), false)) {
            Manifest manifest = archive.getManifest();
            if (manifest == null) {
                throw new IOException("Missing META-INF/MANIFEST.MF: " + normalized);
            }
            Attributes attributes = manifest.getMainAttributes();
            String name = required(attributes, "MIDlet-Name", normalized);
            String entry = firstMidletEntry(attributes, normalized);
            return new J2meGameDescriptor(
                    normalized,
                    name,
                    attributes.getValue("MIDlet-Vendor"),
                    attributes.getValue("MIDlet-Version"),
                    entry);
        }
    }

    private static java.util.Optional<J2meGameDescriptor> tryRead(Path path) {
        try {
            return java.util.Optional.of(read(path));
        } catch (IOException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isJar(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static boolean withinSizeLimit(Path path) {
        try {
            long size = Files.size(path);
            return size > 0 && size <= MAX_JAR_BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String firstMidletEntry(Attributes attributes, Path jar) throws IOException {
        String declaration = required(attributes, "MIDlet-1", jar);
        int comma = declaration.lastIndexOf(',');
        if (comma < 0 || comma == declaration.length() - 1) {
            throw new IOException("Invalid MIDlet-1 declaration in " + jar);
        }
        return declaration.substring(comma + 1).trim();
    }

    private static String required(Attributes attributes, String key, Path jar) throws IOException {
        String value = attributes.getValue(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing " + key + " in " + jar);
        }
        return value.trim();
    }
}
