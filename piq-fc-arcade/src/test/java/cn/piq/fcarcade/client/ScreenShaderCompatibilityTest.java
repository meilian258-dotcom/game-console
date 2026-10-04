package cn.piq.fcarcade.client;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ScreenShaderCompatibilityTest {
    @Test void absentOptionalApiPreservesCustomShaderWithoutRepeatedLookup() {
        var loads = new AtomicInteger();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("fixture.AbsentIrisApi")) { loads.incrementAndGet(); throw new ClassNotFoundException(name); }
                return super.loadClass(name, resolve);
            }
        };
        var query = ScreenShaderCompatibility.discover("fixture.AbsentIrisApi", loader);
        for (int i=0; i<100; i++) assertFalse(query.getAsBoolean());
        assertEquals(1, loads.get());
    }

    @Test void boundQueryTracksEnableDisableAndReloadWithoutRediscoveringInstance() {
        SwitchingApi.instanceCalls = 0; SwitchingApi.queryCalls = 0; SwitchingApi.enabled = false;
        var query = ScreenShaderCompatibility.discover(SwitchingApi.class.getName(), getClass().getClassLoader());
        assertFalse(query.getAsBoolean());
        SwitchingApi.enabled = true; assertTrue(query.getAsBoolean());
        SwitchingApi.enabled = false; assertFalse(query.getAsBoolean());
        SwitchingApi.enabled = true; assertTrue(query.getAsBoolean());
        assertEquals(1, SwitchingApi.instanceCalls);
        assertEquals(4, SwitchingApi.queryCalls);
    }

    @Test void brokenPresentApiUsesKnownShaderRatherThanUnknownShader() {
        var query = ScreenShaderCompatibility.discover(MissingMethodApi.class.getName(), getClass().getClassLoader());
        assertTrue(query.getAsBoolean()); assertTrue(query.getAsBoolean());
    }

    @Test void nullApiInstanceUsesKnownShader() {
        var query = ScreenShaderCompatibility.discover(NullInstanceApi.class.getName(), getClass().getClassLoader());
        assertTrue(query.getAsBoolean());
    }

    @Test void failedQueryIsNotRetriedForEveryScreenAndFrame() {
        FailingApi.calls = 0;
        var query = ScreenShaderCompatibility.discover(FailingApi.class.getName(), getClass().getClassLoader());
        for (int i=0; i<100; i++) assertTrue(query.getAsBoolean());
        assertEquals(1, FailingApi.calls);
    }

    @Test void missingOptionalDependencyIsContainedAfterApiDiscovery() {
        var query = ScreenShaderCompatibility.discover(LinkageFailureApi.class.getName(), getClass().getClassLoader());
        assertTrue(query.getAsBoolean());
    }

    @Test void fatalVmFailuresAreNotHiddenAsOptionalCompatibilityFailures() {
        var query = ScreenShaderCompatibility.discover(FatalApi.class.getName(), getClass().getClassLoader());
        assertThrows(OutOfMemoryError.class, query::getAsBoolean);
    }

    public static final class SwitchingApi {
        static int instanceCalls, queryCalls; static boolean enabled;
        public static SwitchingApi getInstance() { instanceCalls++; return new SwitchingApi(); }
        public boolean isShaderPackInUse() { queryCalls++; return enabled; }
    }
    public static final class MissingMethodApi {
        public static MissingMethodApi getInstance() { return new MissingMethodApi(); }
    }
    public static final class NullInstanceApi {
        public static NullInstanceApi getInstance() { return null; }
        public boolean isShaderPackInUse() { return false; }
    }
    public static final class FailingApi {
        static int calls;
        public static FailingApi getInstance() { return new FailingApi(); }
        public boolean isShaderPackInUse() { calls++; throw new IllegalStateException("fixture API failure"); }
    }
    public static final class LinkageFailureApi {
        public static LinkageFailureApi getInstance() { throw new NoClassDefFoundError("fixture optional dependency"); }
        public boolean isShaderPackInUse() { return false; }
    }
    public static final class FatalApi {
        public static FatalApi getInstance() { return new FatalApi(); }
        public boolean isShaderPackInUse() { throw new OutOfMemoryError("fixture fatal VM error"); }
    }
}
