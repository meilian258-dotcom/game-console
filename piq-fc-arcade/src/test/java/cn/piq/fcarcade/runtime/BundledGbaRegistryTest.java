package cn.piq.fcarcade.runtime;

import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import static cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId.*;
import static org.junit.jupiter.api.Assertions.*;

class BundledGbaRegistryTest {
    @Test void eitherOrderRetainsBothProvidersWithoutOpeningArchives() {
        for (boolean gbaFirst : new boolean[]{false,true}) {
            var registry = new RuntimeInstaller.BundleRegistry();
            var gba = EnumSet.of(GBA); var arcade = EnumSet.of(MAME,NEOGEO_SNAPSHOT);
            registry.register(Path.of(gbaFirst?"absent-gba.jar":"absent-arcade.jar"),gbaFirst?gba:arcade);
            var first = registry.snapshot();
            registry.register(Path.of(gbaFirst?"absent-arcade.jar":"absent-gba.jar"),gbaFirst?arcade:gba);
            assertEquals(EnumSet.allOf(RuntimeCatalog.RuntimeId.class),registry.snapshot().keySet());
            first.forEach((id,source)->assertSame(source,registry.snapshot().get(id)));
            var all = registry.snapshot();
            registry.register(Path.of("absent-gba.jar"),gba);
            registry.register(Path.of("absent-arcade.jar"),arcade);
            assertEquals(all,registry.snapshot());
            assertThrows(UnsupportedOperationException.class,()->all.clear());
        }
    }
    @Test void conflictingOwnershipCannotPartiallyRegisterOrReplace() {
        var registry = new RuntimeInstaller.BundleRegistry();
        registry.register(Path.of("first.jar"),EnumSet.of(GBA));
        var before = registry.snapshot();
        assertThrows(IllegalStateException.class,()->registry.register(Path.of("other.jar"),EnumSet.of(MAME,GBA)));
        assertEquals(before,registry.snapshot());
    }
}
