package cn.piq.retro.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** IO-free server content locations. Resolving a path never imports files or grants content access. */
public final class ServerContentPaths {
    private ServerContentPaths() {}

    public static Path instanceArea(Path instance, String component, String area) {
        return ConsoleStorage.location(instance).resolve(segment(component)).resolve(segment(area));
    }

    public static Path worldArea(Path instance, Path world, String component, String area) {
        return ConsoleStorage.location(instance).resolve("world-content").resolve(worldScope(instance, world))
                .resolve(segment(component)).resolve(segment(area));
    }

    /** Preserves the existing SFC scope: relative location, never an absolute disk/username identity. */
    public static String worldScope(Path instance, Path world) {
        Path relative = absolute(instance).relativize(absolute(world));
        String identity = relative.toString().replace('\\', '/');
        if (identity.isEmpty()) identity = ".";
        String label = identity.equals(".") ? "root" : relative.getFileName().toString();
        label = label.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (label.isBlank() || label.equals("_") || label.equals("..")) label = "world";
        if (label.length() > 24) label = label.substring(0, 24);
        try {
            return label + "-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String segment(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || name.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))
            throw new IllegalArgumentException("Invalid server content path segment");
        return name;
    }

    private static Path absolute(Path path) { return Objects.requireNonNull(path).toAbsolutePath().normalize(); }
}
