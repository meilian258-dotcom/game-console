package cn.piq.retro.client;

import cn.piq.retro.input.GamepadState.Control;
import cn.piq.retro.input.InputProfile;
import cn.piq.retro.input.RetroButtons.Button;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded, explicit-save-only configuration. Does not create a directory merely on opening UI. */
public final class GamepadConfigStore {
    private static final int MAX_BYTES = 65536;
    private GamepadConfigStore() { }
    public record Loaded(GamepadConfig config, String revision, String warning) { }

    public static Loaded load(Path gameDir) throws IOException {
        Path path = path(gameDir); byte[] bytes = read(path);
        if (bytes == null) return new Loaded(GamepadConfig.defaults(), "missing", "");
        try { return new Loaded(decode(bytes), hash(bytes), ""); }
        catch (IllegalArgumentException | NullPointerException failure) {
            return new Loaded(GamepadConfig.defaults().enabled(false), hash(bytes), "配置无效，暂用禁用状态；保存才会替换配置");
        }
    }

    public static Loaded save(Path gameDir, GamepadConfig config, String expectedRevision) throws IOException {
        Path path = path(gameDir);
        byte[] before = read(path);
        if (!Objects.equals(expectedRevision, before == null ? "missing" : hash(before))) throw new IOException("配置已被其他程序修改，请重新打开设置");
        Path parent = path.getParent();
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        validate(parent);
        byte[] data = encode(config);
        Path temporary = Files.createTempFile(parent, "piq-gamepad-", ".tmp");
        try {
            Files.write(temporary, data, StandardOpenOption.TRUNCATE_EXISTING);
            validate(path);
            byte[] now = read(path);
            if (!Arrays.equals(before, now)) throw new IOException("配置保存前发生变化，请重新打开设置");
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("配置目录不支持原子保存，原配置未改动", unsupported); }
            return new Loaded(config, hash(data), "");
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Path path(Path gameDir) throws IOException {
        Path dir = gameDir.toAbsolutePath().normalize(); validate(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("游戏目录不存在");
        Path path = dir.resolve("config/piq-gamepad.properties"); validate(path); return path;
    }

    private static void validate(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path component : path) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("配置路径不能经过链接或特殊文件");
        }
    }

    private static byte[] read(Path path) throws IOException {
        validate(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("配置不是普通文件");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("手柄配置超过 64 KiB");
            return bytes;
        }
    }

    static byte[] encode(GamepadConfig config) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("enabled", Boolean.toString(config.enabled()));
        p.setProperty("device", config.deviceKey()); p.setProperty("deadzone.enter", Float.toString(config.deadzoneEnter()));
        p.setProperty("deadzone.exit", Float.toString(config.deadzoneExit()));
        for (String system : GamepadConfig.SYSTEMS) for (Button button : Button.values()) {
            String controls = String.join(",", config.profiles().get(system).bindings().getOrDefault(button, Set.of()).stream().sorted().map(Enum::name).toList());
            p.setProperty(system + "." + button.name(), controls);
        }
        StringWriter writer = new StringWriter(); p.store(writer, "PIQ local gamepad settings");
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    static GamepadConfig decode(byte[] bytes) throws IOException {
        Properties p = new Properties(); p.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        if (!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("Unknown version");
        String enabled = p.getProperty("enabled");
        if (!"true".equals(enabled) && !"false".equals(enabled)) throw new IllegalArgumentException("Invalid enabled");
        Map<String, InputProfile> profiles = new HashMap<>();
        for (String system : GamepadConfig.SYSTEMS) {
            Map<Button, Set<Control>> bindings = new EnumMap<>(Button.class);
            for (Button button : Button.values()) {
                String raw = Objects.requireNonNull(p.getProperty(system + "." + button.name()), "Missing binding");
                var controls = EnumSet.noneOf(Control.class);
                if (!raw.isEmpty()) for (String value : raw.split(",", -1)) controls.add(Control.valueOf(value));
                bindings.put(button, controls);
            }
            profiles.put(system, new InputProfile(bindings));
        }
        return new GamepadConfig(Boolean.parseBoolean(enabled), p.getProperty("device", ""), Float.parseFloat(p.getProperty("deadzone.enter", "NaN")), Float.parseFloat(p.getProperty("deadzone.exit", "NaN")), profiles);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
