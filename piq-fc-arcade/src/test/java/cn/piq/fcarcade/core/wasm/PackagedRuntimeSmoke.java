package cn.piq.fcarcade.core.wasm;

/**
 * Runs with only the test class directory and the final mod JAR on the
 * classpath. This catches packaging/class-loader failures that ordinary unit
 * tests against Gradle dependency JARs cannot detect.
 */
public final class PackagedRuntimeSmoke {
    private PackagedRuntimeSmoke() {
    }

    public static void main(String[] args) {
        try (WasmNesCore ignored = new WasmNesCore()) {
            // Constructing the core loads the packaged DLL and instantiates WASM.
        }
    }
}
