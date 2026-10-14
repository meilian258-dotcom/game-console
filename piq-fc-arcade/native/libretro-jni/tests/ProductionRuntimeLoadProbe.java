// SPDX-License-Identifier: GPL-3.0-or-later
import cn.piq.retro.libretro.LibretroJniRuntime;
import cn.piq.retro.libretro.LibretroProfile;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Uses the real compiled platform classes, not the separate native ABI fixture. */
public final class ProductionRuntimeLoadProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Object field(String name) throws Exception {
        Field value = NativeLibretroBridge.class.getDeclaredField(name);
        value.setAccessible(true);
        return value.get(null);
    }
    private static long directories(Path instance) throws IOException {
        Path root = instance.resolve("game-console/runtime-sessions");
        if (!Files.exists(root)) return 0;
        try (var paths = Files.list(root)) { return paths.filter(Files::isDirectory).count(); }
    }
    private static boolean causedBy(Throwable failure, Class<?> type, String text) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause())
            if (type.isInstance(cursor) && String.valueOf(cursor.getMessage()).contains(text)) return true;
        return false;
    }
    private static Throwable rejectedPublicCoreLoad() {
        var profile = new LibretroProfile("ProductionLoaderFixture", "bin", false, List.of(1), false,
                Map.of(), Map.of("windows-x64", new LibretroProfile.Artifact(
                        "/core/production-probe-unavailable.dll", "a".repeat(64))));
        try (var runtime = new LibretroJniRuntime(profile, ProductionRuntimeLoadProbe.class)) {
            try { runtime.load(new byte[]{1}); }
            catch (RuntimeException failure) { return failure; }
        }
        throw new AssertionError("Core load was not rejected");
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 3 || args.length == 4, "Expected case, instance, production classes, optional foreign DLL");
        String test = args[0];
        check(Set.of("normal", "wrong-sha", "old-bridge", "foreign-runtime").contains(test), "Unknown case");
        Path instance = Path.of(args[1]).toAbsolutePath().normalize();
        Path classes = Path.of(args[2]).toAbsolutePath().normalize();
        for (Class<?> type : List.of(NativeLibretroBridge.class, LibretroJniRuntime.class, RuntimeWorkspace.class)) {
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            check(actual.equals(classes.toRealPath()), "Production class CodeSource mismatch");
        }
        RuntimeWorkspace.configure(instance);
        check(NativeLibretroBridge.freeSlotsIfLoaded() == 4, "Discovery must not initialize native code");
        check(field("libraryWorkspace") == null && field("libraryPin") == null, "Discovery allocated a workspace");
        if (test.equals("foreign-runtime")) {
            check(args.length == 4, "Foreign runtime path missing");
            System.load(Path.of(args[3]).toAbsolutePath().normalize().toString());
        }
        int cycles = 0;
        if (test.equals("normal")) {
            RuntimeWorkspace original = null;
            for (int cycle = 0; cycle < 3; cycle++) {
                NativeLibretroBridge.load();
                check(NativeLibretroBridge.abiVersion() == 2, "Wrong session ABI");
                check(NativeLibretroBridge.runtimeDependencyApiVersion() == 1, "Wrong dependency API");
                check(NativeLibretroBridge.availableSlots() == 4, "Unexpected occupied slot");
                long token = NativeLibretroBridge.reserve();
                check(NativeLibretroBridge.reservationHeld(token), "Reservation absent");
                check(NativeLibretroBridge.availableSlots() == 3, "Slot was not reserved");
                NativeLibretroBridge.close(token);
                check(!NativeLibretroBridge.reservationHeld(token), "Reservation retained after empty close");
                check(NativeLibretroBridge.availableSlots() == 4, "Slot not recovered");
                var workspace = (RuntimeWorkspace) field("libraryWorkspace");
                if (original == null) original = workspace;
                check(workspace == original, "Repeated load replaced the runtime workspace");
                check(field("libraryPin") != null && Boolean.TRUE.equals(field("loaded")), "Loaded state missing");
                check(field("loadFailure") == null, "Successful load became sticky failure");
                workspace.close();
                check(Files.isRegularFile(workspace.directory().resolve("libc++.dll")), "Active runtime directory was deleted");
                cycles++;
            }
            check(directories(instance) == 1, "Repeated load created another workspace");
        } else if (test.equals("wrong-sha")) {
            for (int cycle = 0; cycle < 2; cycle++) {
                try { NativeLibretroBridge.load(); throw new AssertionError("Wrong runtime SHA accepted"); }
                catch (IOException expected) {
                    check(expected.getMessage().contains("checksum mismatch"), "Wrong rejection stage");
                }
                check(field("libraryWorkspace") == null && field("libraryPin") == null, "Pre-load failure retained native state");
                check(Boolean.FALSE.equals(field("loaded")) && field("loadFailure") == null, "Pre-load rejection became native failure");
                check(directories(instance) == 0, "Pre-load workspace was not cleaned");
            }
            try { NativeLibretroBridge.abiVersion(); throw new AssertionError("Bridge native code unexpectedly available"); }
            catch (UnsatisfiedLinkError expected) { /* No System.load happened in this fresh JVM. */ }
        } else {
            Throwable first = rejectedPublicCoreLoad();
            if (test.equals("old-bridge"))
                check(causedBy(first, UnsatisfiedLinkError.class, "runtimeDependencyApiVersion"), "Old bridge did not fail at capability handshake");
            else
                check(causedBy(first, IOException.class, "Conflicting runtime DLL"), "Foreign runtime did not fail at conflict gate");
            String sticky = (String) field("loadFailure");
            check(sticky != null && sticky.contains("restart required"), "Failure is not sticky");
            check(Boolean.FALSE.equals(field("loaded")), "Failed initialization advertised ready");
            var workspace = (RuntimeWorkspace) field("libraryWorkspace");
            check(workspace != null && field("libraryPin") != null, "Native-attempt workspace/pin lost");
            check(NativeLibretroBridge.freeSlotsIfLoaded() == 0, "Failed bridge did not fail closed");
            check(NativeLibretroBridge.availableSlots() == 4, "A core slot was entered before the loader gate");
            check(!Files.exists(workspace.directory().resolve("core.dll")), "Core staging passed the loader gate");
            check(directories(instance) == 1, "Unexpected core workspace");
            try { NativeLibretroBridge.load(); throw new AssertionError("Sticky failure retried"); }
            catch (IOException second) { check(sticky.equals(second.getMessage()), "Sticky failure changed"); }
            check(field("libraryWorkspace") == workspace, "Retry allocated another workspace");
            workspace.close();
            check(Files.isRegularFile(workspace.directory().resolve("piq-libretro-jni.dll")), "Failed native-attempt directory was deleted");
        }
        System.out.println("PRODUCTION_RUNTIME_RESULT {\"case\":\"" + test + "\",\"passed\":true,\"cycles\":"
                + cycles + ",\"production_classes\":true,\"core_loaded\":false}");
    }
    private ProductionRuntimeLoadProbe() { }
}
