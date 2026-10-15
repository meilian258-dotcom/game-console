// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.retro.replay;

import java.util.Objects;

/**
 * Optional, output-only replay audio contract. This class is supplied once by the host;
 * devices must not bundle a second copy. It has no Minecraft or audio-device dependency.
 * Only the provider's own final, post-volume signed PCM16 little-endian output belongs
 * here, never microphone input or the desktop/system mix.
 */
public final class ReplayAudio {
    public static final int API_VERSION = 1;

    /**
     * A recorder implements this service; an audio provider calls the static facade.
     * Calls may arrive concurrently on output and lifecycle threads. Every method must
     * return promptly: no disk/network I/O, waits, device operations or unbounded work.
     * Capture must make its bounded owned copy before returning; providers may reuse
     * their array immediately. A full recorder queue drops capture, never live audio.
     */
    public interface Service {
        /**
         * Null when inactive. Otherwise bind this opaque token to the current recording,
         * source identity and source epoch. Sources are anonymous, session-local identities;
         * do not stringify, persist or use equals/hashCode on the provider's object.
         */
        Object captureToken(Object source);
        boolean playing();
        /**
         * Receive only bytes actually accepted by the final output. Length is positive
         * and divisible by channels * 2; offset is a byte address, not a sample index.
         * Reject stale recording/source/epoch tokens, including writes returning after
         * recording replacement, reset or close. Never attach old PCM to a new recording.
         */
        void capture(Object token, Object source, byte[] pcm, int offset, int length, int sampleRate, int channels);
        /** Invalidate pending writes before an output flush; do not close the recording. */
        void reset(Object source);
        /** Retire this source and invalidate pending writes; do not close other sources. */
        void close(Object source);
    }

    private static volatile Service service;
    private ReplayAudio() {}

    /** Recorder bootstrap only. Reinstalling the same instance is harmless; replacement is rejected. */
    public static synchronized void install(Service implementation) {
        Objects.requireNonNull(implementation, "implementation");
        if (service != null && service != implementation) throw new IllegalStateException("Replay audio service already installed");
        service = implementation;
    }

    /** Take before a potentially blocking write, then pass unchanged when it returns. */
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

    /** The optional recorder must not alter the original write's return value or exception. */
    public static void capture(Object token, Object source, byte[] pcm, int offset, int length, int sampleRate, int channels) {
        Service current = service;
        if (current == null || token == null) return;
        try { current.capture(token, source, pcm, offset, length, sampleRate, channels); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    public static void reset(Object source) {
        Service current = service;
        try { if (current != null && source != null) current.reset(source); }
        catch (RuntimeException | LinkageError ignored) { }
    }

    public static void close(Object source) {
        Service current = service;
        try { if (current != null && source != null) current.close(source); }
        catch (RuntimeException | LinkageError ignored) { }
    }
}
