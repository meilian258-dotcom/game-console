package cn.piq.fcarcade.cabinet;

/** Trusted addon capability. A diagnostic request is not a promise that every ROM has a menu. */
public final class PgmServicePolicy {
    public static final int DIAGNOSTIC_MASK=8|1024|2048;
    private static final java.util.Set<String> BACKENDS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private PgmServicePolicy() {}
    /** Register only when ALL session profiles reserve Hold Start + L + R for this entry. */
    public static void registerDiagnosticBackend(net.minecraft.resources.ResourceLocation backend){BACKENDS.add(backend.toString());}
    public static boolean supportsBackend(String backend){return BACKENDS.contains(backend);}
    public static int filterInput(int mask,boolean granted){return !granted&&(mask&DIAGNOSTIC_MASK)==DIAGNOSTIC_MASK?mask&~8:mask;}
    public static boolean supports(CabinetGameManifest manifest) {
        return manifest != null && supportsBackend(manifest.backend());
    }
}
