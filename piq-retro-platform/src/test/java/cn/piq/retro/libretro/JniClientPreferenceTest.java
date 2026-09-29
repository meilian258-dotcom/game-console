package cn.piq.retro.libretro;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JniClientPreferenceTest {
    @Test void connectedSupportedClientDefaultsOnWithoutConsent() {
        var p = new JniClientPreference();
        assertTrue(p.enabled(new Object(), true, true));
        assertFalse(p.enabled(null, true, true));
        assertFalse(p.enabled(new Object(), false, true));
        assertFalse(p.enabled(new Object(), true, false));
    }

    @Test void explicitOptOutIsBoundToConnectionIdentityAndCanBeRestored() {
        var p = new JniClientPreference();
        Object first = new String("equal"), next = new String("equal");
        p.decline(first);
        assertFalse(p.enabled(first, true, true));
        assertTrue(p.enabled(next, true, true));
        assertFalse(p.enabled(first, true, true));
        p.reset();
        assertTrue(p.enabled(first, true, true));
    }
}
