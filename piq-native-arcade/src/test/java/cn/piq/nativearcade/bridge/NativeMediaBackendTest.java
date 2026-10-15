package cn.piq.nativearcade.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMediaBackendTest {
    @Test void backendSelectionIsExplicitAndDoesNotGuess() {
        assertEquals(NativeMediaBackend.JNI, NativeMediaBackend.parse("jni"));
        assertEquals(NativeMediaBackend.RETROARCH, NativeMediaBackend.parse("retroarch"));
        for (String unknown : new String[]{"", "auto", "RetroArch", " retroarch ", "process", "server"})
            assertThrows(IllegalArgumentException.class, () -> NativeMediaBackend.parse(unknown));
    }
    @Test void missingLocalPreferenceKeepsCompatibilityDefault() {
        synchronized (NativeMediaBackend.class) {
            String previous = System.getProperty(NativeMediaBackend.PROPERTY);
            try {
                System.clearProperty(NativeMediaBackend.PROPERTY);
                assertEquals(NativeMediaBackend.JNI, NativeMediaBackend.selected());
                System.setProperty(NativeMediaBackend.PROPERTY, "retroarch");
                assertEquals(NativeMediaBackend.RETROARCH, NativeMediaBackend.selected());
                System.setProperty(NativeMediaBackend.PROPERTY, "unknown");
                assertThrows(IllegalArgumentException.class, NativeMediaBackend::selected);
            } finally {
                if (previous == null) System.clearProperty(NativeMediaBackend.PROPERTY);
                else System.setProperty(NativeMediaBackend.PROPERTY, previous);
            }
        }
    }
}
