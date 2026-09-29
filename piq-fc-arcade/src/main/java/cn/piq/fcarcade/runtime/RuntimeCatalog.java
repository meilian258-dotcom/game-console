package cn.piq.fcarcade.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Executable files only. ROMs, firmware, saves and network addresses never belong here. */
public final class RuntimeCatalog {
    private RuntimeCatalog() {}

    public enum RuntimeId { MAME, NEOGEO_SNAPSHOT, GBA }

    public record Artifact(String relativePath, long bytes, String sha256) {
        public Artifact {
            Objects.requireNonNull(relativePath); Objects.requireNonNull(sha256);
            if (!relativePath.matches("(?:piq-native-arcade/(?:runtime|runtime-snapshot-v1)|piq-gba/runtime)/[a-z0-9._-]+\\.(?:dll|jar)")
                    || bytes <= 0 || bytes > 400L * 1024 * 1024 || !sha256.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("Invalid executable catalog entry");
            sha256 = sha256.toUpperCase(Locale.ROOT);
        }
    }

    public record Component(RuntimeId id, String title, List<Artifact> files) {
        public Component { Objects.requireNonNull(id); Objects.requireNonNull(title); files = List.copyOf(files); }
    }

    private static final String JNA_SHA = "34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6";
    private static Artifact file(String root, String name, long bytes, String sha) { return new Artifact(root + "/" + name, bytes, sha); }
    private static Artifact jna(String root) { return file(root, "jna-5.14.0.jar", 1878533, JNA_SHA); }

    // Pins: Native BridgeProtocol + coin-safe v4 helper; NativeSnapshotProfile;
    // GBA check_gba_handheld3 and frozen server-v2-1 runtime. No user-data discovery.
    private static final List<Component> STANDARD = List.of(
        new Component(RuntimeId.MAME, "街机 · 画面传输", List.of(
            file("piq-native-arcade/runtime", "mame_libretro.dll", 372431360, "6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301"),
            file("piq-native-arcade/runtime", "piq-native-helper-v4.jar", 18858, "51A1A6A0A326E5E855647A414DBB78894CA01E9D766627EF272CE59BC809A304"),
            jna("piq-native-arcade/runtime"))),
        new Component(RuntimeId.NEOGEO_SNAPSHOT, "街机 · 本地输入同步", List.of(
            file("piq-native-arcade/runtime-snapshot-v1", "piqneogeo_libretro.dll", 56494080, "E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201"),
            file("piq-native-arcade/runtime-snapshot-v1", "piq-snapshot-helper.jar", 43610, "F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97"),
            jna("piq-native-arcade/runtime-snapshot-v1"))),
        new Component(RuntimeId.GBA, "GBA", List.of(
            file("piq-gba/runtime", "mgba_libretro.dll", 2955998, "D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B"),
            file("piq-gba/runtime", "piq-gba-helper.jar", 20182, "AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C"),
            jna("piq-gba/runtime")))
    );

    public static List<Component> standard() { return STANDARD; }
    /** runtime37 remains the exact original nine-file offline pack, including GBA.
     * The new v4 helper comes from Native 0.1.1's bundle, never from a reinterpreted old ZIP. */
    static List<Artifact> originalOfflinePack() {
        return STANDARD.stream().flatMap(component->component.files().stream()).map(artifact->
                artifact.relativePath().equals("piq-native-arcade/runtime/piq-native-helper-v4.jar")
                        ?file("piq-native-arcade/runtime","piq-native-helper.jar",18571,"20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C")
                        :artifact).toList();
    }
}
