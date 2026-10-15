package cn.piq.replaycompat.client;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenVertex;
import java.util.*;
import java.util.function.Predicate;

/** Render-thread-only content/geometry decisions. No GPU, live device, clock, or archive access. */
public final class ScreenPresentationCache {
    public static final int MAX_SLOTS = 16;
    /** Version is changed only when a fixed texture slot is assigned different pixel content. */
    public record Image(int slot, long version, ScreenFrame pixels, int hash) {}
    /** The current exact frame supplies geometry/time; an image never does. */
    public record Draw(ScreenFrame frame, Image image) {}
    public record Plan(List<Draw> draws, List<Image> retainedImages) {
        public Plan { draws = List.copyOf(draws); retainedImages = List.copyOf(retainedImages); }
    }
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public record Metrics(long hashes, long comparisons, long assignments) {}
    private record Binding(ScreenFrame lastFrame, Image image) {}
    private record Candidate(ScreenFrame frame, int hash) {}
    private final Image[] slots = new Image[MAX_SLOTS];
    private Map<String, Binding> bindings = Map.of();
    private long version, hashes, comparisons, assignments;

    /** Active pending sources may retain allocations, but only supplied exact frames are drawn. */
    public Plan select(List<ScreenFrame> frames, Set<String> activeSources, String dimension,
                       Predicate<Bounds> visible) {
        Objects.requireNonNull(frames); Objects.requireNonNull(activeSources);
        Objects.requireNonNull(dimension); Objects.requireNonNull(visible);
        var selected = new LinkedHashMap<String, ScreenFrame>();
        var invalid = new HashSet<String>();
        for (ScreenFrame frame : frames) {
            if (frame.closed() || !dimension.equals(frame.dimension())) { invalid.add(frame.source()); continue; }
            if (selected.size() < MAX_SLOTS && activeSources.contains(frame.source())
                    && visible.test(bounds(frame))) selected.putIfAbsent(frame.source(), frame);
        }
        selected.keySet().removeAll(invalid);
        var next = new LinkedHashMap<String, Binding>();
        var pending = new ArrayList<Candidate>();
        boolean[] reserved = new boolean[MAX_SLOTS];
        // Resolve every hit before replacing any slot: this prevents an early changed source
        // from evicting an unchanged later screen while all sixteen texture slots are occupied.
        for (ScreenFrame frame : selected.values()) {
            Binding previous = bindings.get(frame.source());
            if (previous != null && previous.lastFrame == frame && slots[previous.image.slot] == previous.image) {
                next.put(frame.source(), previous); reserved[previous.image.slot] = true; continue;
            }
            int hash = frame.pixelHash(); hashes++;
            Image match = find(frame, hash);
            if (match == null) pending.add(new Candidate(frame, hash));
            else { next.put(frame.source(), new Binding(frame, match)); reserved[match.slot] = true; }
        }
        for (Candidate candidate : pending) {
            Image match = find(candidate.frame, candidate.hash);
            if (match == null) {
                int slot = available(reserved);
                if (slot < 0) throw new IllegalStateException("Bounded presentation slots exhausted");
                match = new Image(slot, ++version, candidate.frame, candidate.hash);
                slots[slot] = match; assignments++;
            }
            next.put(candidate.frame.source(), new Binding(candidate.frame, match)); reserved[match.slot] = true;
        }
        // Keep still-active pending/off-frustum allocations if they have not been displaced.
        // They are not part of the Draw list and therefore cannot show old seek-time pixels.
        for (var entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            if (next.size() < MAX_SLOTS && !next.containsKey(entry.getKey()) && !invalid.contains(entry.getKey())
                    && activeSources.contains(entry.getKey()) && dimension.equals(binding.lastFrame.dimension())
                    && slots[binding.image.slot] == binding.image) next.put(entry.getKey(), binding);
        }
        boolean[] retained = new boolean[MAX_SLOTS];
        for (Binding binding : next.values()) retained[binding.image.slot] = true;
        var images = new ArrayList<Image>();
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if (!retained[slot]) slots[slot] = null;
            else images.add(slots[slot]);
        }
        var draws = new ArrayList<Draw>();
        for (ScreenFrame frame : selected.values()) draws.add(new Draw(frame, next.get(frame.source()).image));
        bindings = next;
        return new Plan(draws, images);
    }

    private Image find(ScreenFrame frame, int hash) {
        for (Image image : slots) {
            if (image == null || image.hash != hash || image.pixels.width() != frame.width()
                    || image.pixels.height() != frame.height()) continue;
            comparisons++;
            if (image.pixels.samePixels(frame)) return image;
        }
        return null;
    }
    private int available(boolean[] reserved) {
        for (int slot = 0; slot < MAX_SLOTS; slot++) if (slots[slot] == null && !reserved[slot]) return slot;
        for (int slot = 0; slot < MAX_SLOTS; slot++) if (!reserved[slot]) return slot;
        return -1;
    }
    public Metrics metrics() { return new Metrics(hashes, comparisons, assignments); }
    public void clear() { Arrays.fill(slots, null); bindings = Map.of(); }

    /** World-space mesh bounds, independent of player position or live watching distance. */
    public static Bounds bounds(ScreenFrame frame) {
        if (frame.closed()) throw new IllegalArgumentException("Closed source has no mesh bounds");
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (ScreenVertex vertex : frame.vertices()) {
            minX = Math.min(minX, vertex.x()); minY = Math.min(minY, vertex.y()); minZ = Math.min(minZ, vertex.z());
            maxX = Math.max(maxX, vertex.x()); maxY = Math.max(maxY, vertex.y()); maxZ = Math.max(maxZ, vertex.z());
        }
        // Avoid zero-thickness boxes at the frustum plane and float rounding at large coordinates.
        double epsilon = Math.max(.01, Math.ulp((float) Math.max(Math.max(Math.abs(minX), Math.abs(maxX)),
                Math.max(Math.abs(minZ), Math.abs(maxZ)))) * 2.0);
        return new Bounds(minX - epsilon, minY - epsilon, minZ - epsilon,
                maxX + epsilon, maxY + epsilon, maxZ + epsilon);
    }
}
