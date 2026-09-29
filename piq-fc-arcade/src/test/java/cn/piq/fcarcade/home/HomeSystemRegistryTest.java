package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeSystemRegistryTest {
    @Test void setupRegistersAndResolvesEachIndependentSystem() {
        var registry = new HomeSystemRegistry<String,Object>("nes");
        Object sfc = new Object(), other = new Object();
        registry.register("sfc",sfc); registry.register("other",other);
        assertSame(sfc,registry.get("sfc")); assertSame(other,registry.get("other"));
    }
    @Test void duplicateDoesNotReplaceOriginalHooks() {
        var registry = new HomeSystemRegistry<String,Object>("nes"); Object first=new Object();
        registry.register("sfc",first);
        assertThrows(IllegalArgumentException.class,()->registry.register("sfc",new Object()));
        assertSame(first,registry.get("sfc"));
    }
    @Test void ReservedNesIdCannotBeHijacked() {
        var registry = new HomeSystemRegistry<String,Object>("nes");
        assertThrows(IllegalArgumentException.class,()->registry.register("nes",new Object()));
        assertNull(registry.get("nes"));
    }
    @Test void runtimeRegistrationIsRejectedEvenForNewIds() {
        var registry = new HomeSystemRegistry<String,Object>("nes"); registry.lock();
        assertThrows(IllegalStateException.class,()->registry.register("sfc",new Object()));
        assertNull(registry.get("sfc"));
    }
    @Test void LockPreservesSetupEntriesAndIsIdempotent() {
        var registry = new HomeSystemRegistry<String,Object>("nes"); Object first=new Object();
        registry.register("sfc",first); registry.lock(); registry.lock();
        assertSame(first,registry.get("sfc"));
        assertThrows(IllegalStateException.class,()->registry.register("sfc",new Object()));
    }
    @Test void UnknownSystemsFailClosed() {
        assertNull(new HomeSystemRegistry<String,Object>("nes").get("sfc"));
    }
    @Test void NullIdsAndProvidersCannotEnterTheRegistry() {
        var registry = new HomeSystemRegistry<String,Object>("nes");
        assertThrows(NullPointerException.class,()->registry.register(null,new Object()));
        assertThrows(NullPointerException.class,()->registry.register("sfc",null));
    }
}
