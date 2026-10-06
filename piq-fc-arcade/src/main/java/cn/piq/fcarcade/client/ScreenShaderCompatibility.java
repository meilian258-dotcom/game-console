package cn.piq.fcarcade.client;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional client-only Iris API lookup; no Iris classes are linked into the mod. */
final class ScreenShaderCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenShaderCompatibility.class);
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private ScreenShaderCompatibility() { }

    static BooleanSupplier discover() {
        return discover(IRIS_API, ScreenShaderCompatibility.class.getClassLoader());
    }

    // Resolve once, but query pack activity on each draw so toggling/reloading shaders is safe.
    // The draw path invokes a bound MethodHandle; it never performs reflective method discovery.
    static BooleanSupplier discover(String apiName, ClassLoader loader) {
        final Class<?> apiClass;
        try {
            apiClass = Class.forName(apiName, false, loader);
        } catch (ClassNotFoundException absent) {
            return () -> false;
        } catch (LinkageError broken) {
            return unavailable(broken);
        }
        try {
            var lookup = MethodHandles.publicLookup();
            var instance = lookup.findStatic(apiClass, "getInstance", MethodType.methodType(apiClass)).invoke();
            if (instance == null) throw new IllegalStateException("Iris API returned no instance");
            var query = lookup.findVirtual(apiClass, "isShaderPackInUse", MethodType.methodType(boolean.class))
                    .bindTo(instance);
            return new BooleanSupplier() {
                private boolean failed;

                @Override public boolean getAsBoolean() {
                    if (failed) return true;
                    try {
                        return (boolean) query.invokeExact();
                    } catch (Throwable failure) {
                        rethrowFatal(failure);
                        failed = true;
                        warn(failure);
                        return true;
                    }
                }
            };
        } catch (Throwable broken) {
            rethrowFatal(broken);
            return unavailable(broken);
        }
    }

    private static BooleanSupplier unavailable(Throwable failure) {
        warn(failure);
        // An installed but incompatible Iris API must not force our unknown shader back in.
        // The vanilla opaque entity shader is valid even with no active pack; display vertices
        // retain FULL_BRIGHT light and NO_OVERLAY, with the normal samplers bound by the material.
        return () -> true;
    }

    private static void warn(Throwable failure) {
        LOGGER.warn("PIQ screen: Iris API unavailable; using the vanilla opaque entity screen shader", failure);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath death) throw death;
    }
}
