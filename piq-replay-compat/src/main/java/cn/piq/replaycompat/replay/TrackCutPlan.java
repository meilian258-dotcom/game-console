package cn.piq.replaycompat.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A screen-track projection for the supported ReForgedPlay 0.3 recording markers.
 *
 * <p>Output indexes follow every processed split, not the number of nonempty
 * screen tracks or MarkerProcessor's output-suffix prediction. The caller must
 * bind these indexes to actual output-file save callbacks; the upstream packet
 * processor can stop before all planned outputs are created.</p>
 *
 * <p>The input duration is the last valid source timestamp, inclusive. Markers
 * beyond it cannot be reached and are ignored. This assumes the duration matches
 * the packet stream's last timestamp; actual output metadata remains authoritative.
 * Ambiguous or malformed control sequences are rejected rather than silently
 * repaired or claimed to reproduce upstream's undefined ordering.</p>
 */
public final class TrackCutPlan {
    public static final String START_CUT = "_RM_START_CUT";
    public static final String END_CUT = "_RM_END_CUT";
    public static final String SPLIT = "_RM_SPLIT";

    public record Marker(int time, String name) { }

    /**
     * Retained half-open source range, with an affine output timestamp mapping.
     * At its start, seed the track at {@code outputFrom} with the state immediately
     * before {@code sourceFrom}, then apply source events at that timestamp.
     * A seed must include removals/blank states, not just the last nonempty image.
     */
    public record Segment(long sourceFrom, long sourceTo, long outputFrom) {
        public Segment {
            if (sourceFrom < 0 || sourceTo <= sourceFrom || outputFrom < 0) {
                throw new IllegalArgumentException("Invalid retained segment");
            }
        }

        public OptionalLong map(long sourceTime) {
            return sourceTime >= sourceFrom && sourceTime < sourceTo
                    ? OptionalLong.of(outputFrom + sourceTime - sourceFrom)
                    : OptionalLong.empty();
        }
    }

    /** An output may have no retained segments; its index must still be kept. */
    public record Output(int index, List<Segment> segments) {
        public Output {
            if (index < 0) {
                throw new IllegalArgumentException("Negative output index");
            }
            segments = List.copyOf(segments);
        }

        public OptionalLong map(long sourceTime) {
            for (Segment segment : segments) {
                OptionalLong mapped = segment.map(sourceTime);
                if (mapped.isPresent()) {
                    return mapped;
                }
            }
            return OptionalLong.empty();
        }
    }

    private final List<Output> outputs;

    private TrackCutPlan(List<Output> outputs) {
        this.outputs = List.copyOf(outputs);
    }

    public List<Output> outputs() {
        return outputs;
    }

    public Output output(int outputIndex) {
        return outputs.get(outputIndex);
    }

    public OptionalLong map(int outputIndex, long sourceTime) {
        return output(outputIndex).map(sourceTime);
    }

    public static TrackCutPlan create(int duration, List<Marker> markers) {
        if (duration < 0) {
            throw new IllegalArgumentException("Negative input duration");
        }
        Objects.requireNonNull(markers, "markers");
        List<Marker> controls = new ArrayList<>();
        for (Marker marker : markers) {
            Objects.requireNonNull(marker, "marker");
            String name = marker.name();
            boolean control = START_CUT.equals(name) || END_CUT.equals(name) || SPLIT.equals(name);
            if (!control) {
                if (name != null && name.startsWith("_RM_")) {
                    throw new IllegalArgumentException("Unsupported recording marker: " + name);
                }
                continue;
            }
            if (marker.time() < 0) {
                throw new IllegalArgumentException("Negative control-marker timestamp");
            }
            if (marker.time() <= duration) {
                controls.add(marker);
            }
        }
        controls.sort(Comparator.comparingInt(Marker::time));

        List<Output> outputs = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        long segmentStart = 0;
        long timeOffset = 0;
        long startCut = 0;
        int previousTime = -1;
        boolean cutting = false;
        for (Marker marker : controls) {
            int time = marker.time();
            if (time == previousTime) {
                throw new IllegalArgumentException("Ambiguous control markers at timestamp " + time);
            }
            previousTime = time;
            switch (marker.name()) {
                case START_CUT -> {
                    if (cutting) {
                        throw new IllegalArgumentException("Nested start-cut marker at " + time);
                    }
                    addSegment(segments, segmentStart, time, timeOffset);
                    cutting = true;
                    startCut = time;
                }
                case END_CUT -> {
                    if (!cutting) {
                        throw new IllegalArgumentException("Unmatched end-cut marker at " + time);
                    }
                    timeOffset += time - startCut;
                    cutting = false;
                    segmentStart = time;
                }
                case SPLIT -> {
                    if (!cutting) {
                        addSegment(segments, segmentStart, time, timeOffset);
                    }
                    outputs.add(new Output(outputs.size(), segments));
                    segments = new ArrayList<>();
                    // Upstream resets both offsets even when splitting a cut.
                    timeOffset = time;
                    startCut = time;
                    segmentStart = time;
                }
                default -> throw new AssertionError("Unclassified control marker");
            }
        }
        if (!cutting) {
            // The recording's final millisecond is a valid frame timestamp.
            addSegment(segments, segmentStart, (long) duration + 1, timeOffset);
        }
        outputs.add(new Output(outputs.size(), segments));
        return new TrackCutPlan(outputs);
    }

    private static void addSegment(List<Segment> segments, long from, long to, long offset) {
        if (from < to) {
            segments.add(new Segment(from, to, from - offset));
        }
    }
}
