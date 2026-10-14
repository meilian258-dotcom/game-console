package cn.piq.replaycompat.api;

import java.util.Objects;

/** Optional output-only audio API v1. Compile-only; never bundle a second copy in a device.
 * The provider submits only its own final PCM output, never the desktop/system mix.
 * Volume has already been applied. Arrays are copied synchronously by the installed service.
 */
public final class AudioReplay {
    public static final int API_VERSION = 1;
    public interface Service {
        Object captureToken();
        default Object captureToken(Object source) { return captureToken(); }
        boolean playing();
        void capture(Object token, Object source, byte[] pcm, int offset, int length, int sampleRate, int channels);
        void reset(Object source);
        void close(Object source);
    }
    private static volatile Service service;
    private AudioReplay() {}
    /** Transport installation only, not a device registration API. */
    public static synchronized void install(Service implementation) {
        Objects.requireNonNull(implementation);
        if (service != null && service != implementation) throw new IllegalStateException("Audio service already installed");
        service = implementation;
    }
    /** Take before a potentially blocking device write; pass unchanged after that write returns. */
    public static Object captureToken() {
        Service current = service;
        try { return current == null ? null : current.captureToken(); }
        catch (RuntimeException | LinkageError ignored) { return null; }
    }
    public static boolean recording() { return captureToken() != null; }
    public static Object captureToken(Object source) {
        Service current = service;
        try { return current == null || source == null ? null : current.captureToken(source); }
        catch (RuntimeException | LinkageError ignored) { return null; }
    }
    public static boolean playing() {
        Service current = service;
        try { return current != null && current.playing(); }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }
    public static void capture(Object source, byte[] pcm, int offset, int length, int sampleRate, int channels) {
        capture(captureToken(source), source, pcm, offset, length, sampleRate, channels);
    }
    /** Invalid/failed optional capture never changes the original device write's result or exception. */
    public static void capture(Object token, Object source, byte[] pcm, int offset, int length, int sampleRate, int channels) {
        Service current = service;
        if (current == null || token == null) return;
        try { current.capture(token, source, pcm, offset, length, sampleRate, channels); }
        catch (RuntimeException | LinkageError ignored) { /* Optional branch cannot break playback. */ }
    }
    public static void reset(Object source) {
        Service current = service;
        try { if (current != null) current.reset(source); }
        catch (RuntimeException | LinkageError ignored) { }
    }
    public static void close(Object source) {
        Service current = service;
        try { if (current != null) current.close(source); }
        catch (RuntimeException | LinkageError ignored) { }
    }
}
