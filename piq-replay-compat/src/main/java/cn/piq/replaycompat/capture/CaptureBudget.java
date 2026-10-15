package cn.piq.replaycompat.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Render-thread admission, independent of game classes so limits can be tested. */
public final class CaptureBudget {
    public static final int MAX_SCREENS = 16;
    public static final int MAX_PIXELS = 1024 * 1024;
    public static final int MAX_VERTICES = 4096;
    public static final long FRAME_MILLIS = 50;
    public static final long ABSENCE_MILLIS = 300;
    private record Seen(long observed, long nextFrame) {}
    private final Map<String, Seen> screens = new LinkedHashMap<>();

    public boolean admit(String source, long time) {
        Seen old = screens.get(source);
        if (old == null && screens.size() >= MAX_SCREENS) return false;
        boolean reset = old == null || time < old.observed;
        boolean ready = reset || time >= old.nextFrame;
        screens.put(source, new Seen(time, ready ? nextFrame(time, reset ? time : old.nextFrame) : old.nextFrame));
        return ready;
    }

    /** Carry the deadline phase across render jitter. Missed intervals never cause catch-up work. */
    public static long nextFrame(long time, long deadline) {
        return time + FRAME_MILLIS - Math.floorMod(time - deadline, FRAME_MILLIS);
    }

    public List<String> expired(long time) {
        List<String> expired = new ArrayList<>();
        screens.entrySet().removeIf(entry -> {
            long observed = entry.getValue().observed;
            boolean remove = time < observed || time - observed > ABSENCE_MILLIS;
            if (remove) expired.add(entry.getKey());
            return remove;
        });
        return expired;
    }

    public void clear() { screens.clear(); }

    public static boolean validPixels(int width, int height) {
        return width > 0 && height > 0 && width <= 1024 && height <= 1024
                && (long) width * height <= MAX_PIXELS;
    }

    /** A geometry identity, never a transient DynamicTexture allocation name. */
    public static String source(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_001 || Math.abs(y) > 30_000_001 || Math.abs(z) > 30_000_001)
            throw new IllegalArgumentException("screen position");
        // Rounding to a quarter block tolerates camera-relative float precision noise.
        return "legacy-screen/" + Math.round(x * 4) + "/" + Math.round(y * 4) + "/" + Math.round(z * 4);
    }
}
