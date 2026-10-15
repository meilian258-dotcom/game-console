package cn.piq.retro.replay;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** The actual public class, loaded in isolation; no global service reset hook is needed. */
class ReplayAudioTest {
    private static final String API = "cn.piq.retro.replay.ReplayAudio";
    private static final Class<?>[] CAPTURE = {Object.class, Object.class, byte[].class,
            int.class, int.class, int.class, int.class};

    private static final class Isolated extends ClassLoader {
        final Class<?> api, service;
        Isolated() throws ClassNotFoundException {
            super(ClassLoader.getPlatformClassLoader());
            api = loadClass(API); service = loadClass(API + "$Service");
        }
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(API) && !name.equals(API + "$Service")) throw new ClassNotFoundException(name);
            try (var in = ReplayAudioTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                byte[] bytes = in.readAllBytes(); return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
        }
        Object invoke(String name, Class<?>[] types, Object... arguments) throws Throwable {
            try { return api.getMethod(name, types).invoke(null, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        Object sourceCall(String name, Object source) throws Throwable {
            return invoke(name, new Class<?>[]{Object.class}, source);
        }
        void install(java.lang.reflect.InvocationHandler handler) throws Throwable {
            invoke("install", new Class<?>[]{service}, Proxy.newProxyInstance(this, new Class<?>[]{service}, handler));
        }
    }

    @Test void absentServiceIsInertAndNeedsOnlyJdkClasses() throws Throwable {
        var api = new Isolated(); var source = new Object();
        assertEquals(1, api.api.getField("API_VERSION").getInt(null));
        assertNull(api.sourceCall("captureToken", source));
        assertEquals(false, api.invoke("playing", new Class<?>[0]));
        api.invoke("capture", CAPTURE, new Object(), source, new byte[4], 0, 4, 48000, 2);
        api.sourceCall("reset", source); api.sourceCall("close", source);
    }

    @Test void exactArgumentsIdentityAndSynchronousCopyOnTheCallingThread() throws Throwable {
        var api = new Isolated(); var token = new Object();
        Object source = new Object() {
            public int hashCode() { throw new AssertionError("source must remain anonymous"); }
            public boolean equals(Object value) { throw new AssertionError("identity, not equality"); }
            public String toString() { throw new AssertionError("not an identifier to persist"); }
        };
        byte[] pcm = {9, 1, 2, 3, 4, 8}; var copied = new AtomicReference<byte[]>();
        Thread caller = Thread.currentThread();
        api.install((ignored, method, args) -> {
            assertSame(caller, Thread.currentThread());
            switch (method.getName()) {
                case "captureToken": assertSame(source, args[0]); return token;
                case "playing": return true;
                case "capture":
                    assertSame(token, args[0]); assertSame(source, args[1]); assertSame(pcm, args[2]);
                    assertEquals(1, args[3]); assertEquals(4, args[4]);
                    assertEquals(48000, args[5]); assertEquals(2, args[6]);
                    copied.set(Arrays.copyOfRange((byte[]) args[2], (int) args[3], (int) args[3] + (int) args[4]));
                    return null;
                case "reset", "close": assertSame(source, args[0]); return null;
                default: throw new AssertionError(method);
            }
        });
        assertSame(token, api.sourceCall("captureToken", source));
        assertEquals(true, api.invoke("playing", new Class<?>[0]));
        api.invoke("capture", CAPTURE, token, source, pcm, 1, 4, 48000, 2);
        Arrays.fill(pcm, (byte) 0); assertArrayEquals(new byte[]{1, 2, 3, 4}, copied.get());
        api.sourceCall("reset", source); api.sourceCall("close", source);
    }

    @Test void nullSourceAndInactiveTokenNeverCallTheService() throws Throwable {
        var api = new Isolated();
        api.install((ignored, method, args) -> { throw new AssertionError("unexpected " + method.getName()); });
        assertNull(api.sourceCall("captureToken", null));
        api.sourceCall("reset", null); api.sourceCall("close", null);
        api.invoke("capture", CAPTURE, null, new Object(), new byte[4], 0, 4, 48000, 2);
    }

    @Test void optionalRuntimeAndLinkageFailuresAreIsolated() throws Throwable {
        for (Throwable failure : new Throwable[]{new IllegalStateException("recorder failed"), new NoClassDefFoundError("optional recorder")}) {
            var api = new Isolated(); var source = new Object();
            api.install((ignored, method, args) -> { throw failure; });
            assertNull(api.sourceCall("captureToken", source));
            assertEquals(false, api.invoke("playing", new Class<?>[0]));
            api.invoke("capture", CAPTURE, new Object(), source, new byte[4], 0, 4, 48000, 2);
            api.sourceCall("reset", source); api.sourceCall("close", source);
        }
    }

    @Test void bootstrapRejectsReplacementButAllowsTheSameService() throws Throwable {
        var api = new Isolated();
        var implementation = Proxy.newProxyInstance(api, new Class<?>[]{api.service}, (ignored, method, args) -> null);
        api.invoke("install", new Class<?>[]{api.service}, implementation);
        api.invoke("install", new Class<?>[]{api.service}, implementation);
        assertThrows(IllegalStateException.class, () -> api.install((ignored, method, args) -> null));
        assertThrows(NullPointerException.class, () -> api.invoke("install", new Class<?>[]{api.service}, new Object[]{null}));
    }

    @Test void lifecycleThreadIsNotSerializedBehindAnOutputCall() throws Throwable {
        var api = new Isolated(); var source = new Object(); var token = new Object();
        var entered = new CountDownLatch(1); var released = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        // The deliberately blocked fake service proves the facade holds no lock around callbacks.
        api.install((ignored, method, args) -> {
            if (method.getName().equals("captureToken")) {
                entered.countDown(); assertTrue(released.await(2, TimeUnit.SECONDS)); return token;
            }
            if (method.getName().equals("close")) released.countDown();
            return null;
        });
        Thread output = Thread.ofPlatform().daemon().start(() -> {
            try { assertSame(token, api.sourceCall("captureToken", source)); }
            catch (Throwable failure) { error.set(failure); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            api.sourceCall("close", source); output.join(2000);
            assertFalse(output.isAlive()); assertNull(error.get());
        } finally { released.countDown(); output.join(2000); }
    }
}
