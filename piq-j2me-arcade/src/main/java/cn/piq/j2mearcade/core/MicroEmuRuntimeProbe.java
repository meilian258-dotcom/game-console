package cn.piq.j2mearcade.core;

public final class MicroEmuRuntimeProbe {
    private MicroEmuRuntimeProbe() {
    }

    public static Result probe() {
        try {
            Class.forName("org.microemu.MicroEmulator", false,
                    MicroEmuRuntimeProbe.class.getClassLoader());
            Class<?> deviceType = Class.forName("org.microemu.device.j2se.J2SEDevice", false,
                    MicroEmuRuntimeProbe.class.getClassLoader());
            deviceType.getConstructor().newInstance();
            return new Result(true, System.getProperty("java.version"), null);
        } catch (Throwable error) {
            return new Result(false, System.getProperty("java.version"), error);
        }
    }

    public record Result(boolean available, String javaVersion, Throwable error) {
    }
}
