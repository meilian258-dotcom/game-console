package cn.piq.computer.client;

/** Pure choice policy: preserve explicit/unknown legacy values and fail closed on unreadable settings. */
final class PvzBackendPreference {
    private PvzBackendPreference() {}
    static boolean jni(String configured, boolean supported, boolean settingsReadable) {
        return supported && settingsReadable && (configured == null || configured.equals("jni-v1"));
    }
}
