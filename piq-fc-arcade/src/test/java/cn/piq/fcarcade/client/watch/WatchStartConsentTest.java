package cn.piq.fcarcade.client.watch;

import cn.piq.retro.libretro.JniClientPreference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchStartConsentTest {
    @Test void jniEnabledOnSupportedLiveConnectionMayStart() {
        var preference=new JniClientPreference();var connection=new Object();
        assertDoesNotThrow(()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(connection,true,true)));
    }

    @Test void optOutDuringContentPreparationIsReadAtFinalStart() {
        var preference=new JniClientPreference();var connection=new Object();
        assertTrue(preference.enabled(connection,true,true)); // Before asynchronous preparation.
        preference.decline(connection);
        assertThrows(IllegalStateException.class,()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(connection,true,true)));
    }

    @Test void restoringConsentBeforeFinalStartIsNotBlockedByAnOldSnapshot() {
        var preference=new JniClientPreference();var connection=new Object();
        preference.decline(connection);assertFalse(preference.enabled(connection,true,true));
        preference.reset();
        assertDoesNotThrow(()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(connection,true,true)));
    }

    @Test void processBackendNeverConsultsJniConsent() {
        assertDoesNotThrow(()->WatchStartConsent.requireAllowed(false,
                ()->{throw new AssertionError("RetroArch must not query JNI consent");}));
        assertDoesNotThrow(()->WatchStartConsent.requireAllowed(false,()->false));
    }

    @Test void unsupportedDisconnectedAndMissingConnectionsRejectJni() {
        var preference=new JniClientPreference();var connection=new Object();
        assertThrows(IllegalStateException.class,()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(connection,true,false)));
        assertThrows(IllegalStateException.class,()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(connection,false,true)));
        assertThrows(IllegalStateException.class,()->WatchStartConsent.requireAllowed(true,
                ()->preference.enabled(null,true,true)));
    }

    @Test void refusalPreventsFollowingOpenAndRemainsCatchable() {
        var opens=new AtomicInteger();var failures=new AtomicInteger();
        try {
            WatchStartConsent.requireAllowed(true,()->false);
            opens.incrementAndGet();
            fail("Denied JNI must not reach open");
        } catch (IllegalStateException denied) {
            failures.incrementAndGet();
            assertTrue(denied.getMessage().contains("/gameconsole-jni-netplay"));
        }
        assertEquals(0,opens.get());assertEquals(1,failures.get());
    }

    @Test void queryFailuresAreNotConvertedToConsentOrBackendFallback() {
        var failure=new IllegalStateException("unavailable");
        assertSame(failure,assertThrows(IllegalStateException.class,
                ()->WatchStartConsent.requireAllowed(true,()->{throw failure;})));
        var missing=new NoClassDefFoundError("missing preference");
        assertSame(missing,assertThrows(NoClassDefFoundError.class,
                ()->WatchStartConsent.requireAllowed(true,()->{throw missing;})));
    }

    @Test void allowedQueryIsReadExactlyOnceAndDoesNotChangeThePreference() {
        var preference=new JniClientPreference();var connection=new Object();var calls=new AtomicInteger();
        preference.decline(connection);
        assertThrows(IllegalStateException.class,()->WatchStartConsent.requireAllowed(true,()->{
            calls.incrementAndGet();return preference.enabled(connection,true,true);
        }));
        assertEquals(1,calls.get());assertFalse(preference.enabled(connection,true,true));
    }
}
