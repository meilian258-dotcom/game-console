package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSyncRelease44Test {
    private RuntimeCatalog.Component local() {
        return RuntimeCatalog.standard().stream()
                .filter(c -> c.id() == RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT).findFirst().orElseThrow();
    }
    @Test void releasedDisplayNameKeepsItsPersistentRuntimeId() {
        assertEquals("街机 · 本地输入同步", local().title());
        assertEquals(1, local().id().ordinal());
    }
    @Test void releaseRetainsAllThreeExecutablePinsAndPaths() {
        var files = local().files();
        assertEquals(List.of("piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll",
                "piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar",
                "piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar"),
                files.stream().map(RuntimeCatalog.Artifact::relativePath).toList());
        assertEquals(List.of(56494080L, 43610L, 1878533L),
                files.stream().map(RuntimeCatalog.Artifact::bytes).toList());
        assertEquals(List.of("E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201",
                "F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97",
                "34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6"),
                files.stream().map(RuntimeCatalog.Artifact::sha256).toList());
    }
    @Test void releaseDoesNotAddBiosOrGamesToExecutableCatalog() {
        assertEquals(3, RuntimeCatalog.standard().size());
        assertEquals(9, RuntimeCatalog.standard().stream().mapToInt(c -> c.files().size()).sum());
        assertTrue(RuntimeCatalog.standard().stream().flatMap(c -> c.files().stream())
                .allMatch(f -> f.relativePath().endsWith(".jar") || f.relativePath().endsWith(".dll")));
    }
}
