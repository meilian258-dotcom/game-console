package cn.piq.fcarcade.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

public final class LockstepDigestTracker {
    public static final long INTERVAL_FRAMES = 300;
    public static final long MAX_LAG_FRAMES = 600;

    private final Map<Long, Map<UUID, Long>> reports = new HashMap<>();
    private int epoch;
    private long lastCompletedFrame;
    private UUID referencePlayer;
    private final Map<Long, Map<UUID, Long>> referenceReports = new HashMap<>();
    private final Map<Long, Set<UUID>> comparedReports = new HashMap<>();

    public void reset(int nextEpoch) {
        epoch = nextEpoch;
        lastCompletedFrame = 0;
        reports.clear();
        referenceReports.clear();
        comparedReports.clear();
        referencePlayer = null;
    }

    /** Only the designated controller is a reference. Observers never vote on global state. */
    public Comparison compareToReference(
            UUID playerId, UUID referenceId, int reportEpoch, long reportFrame,
            long currentFrame, long digest, Set<UUID> participants
    ) {
        if (reportEpoch != epoch || referenceId == null
                || !participants.contains(referenceId) || !participants.contains(playerId)
                || reportFrame <= 0 || reportFrame % INTERVAL_FRAMES != 0
                || reportFrame > currentFrame || currentFrame - reportFrame > MAX_LAG_FRAMES) {
            return new Comparison(Result.REJECTED, Set.of());
        }
        if (!referenceId.equals(referencePlayer)) {
            referenceReports.clear();
            comparedReports.clear();
            referencePlayer = referenceId;
        }
        referenceReports.keySet().removeIf(frame -> currentFrame - frame > MAX_LAG_FRAMES);
        comparedReports.keySet().removeIf(frame -> currentFrame - frame > MAX_LAG_FRAMES);
        Map<UUID, Long> frameReports = referenceReports.computeIfAbsent(
                reportFrame, ignored -> new HashMap<>());
        if (frameReports.putIfAbsent(playerId, digest) != null) {
            return new Comparison(Result.REJECTED, Set.of());
        }
        Long expected = frameReports.get(referenceId);
        if (expected == null) return new Comparison(Result.WAITING, Set.of());
        Set<UUID> compared = comparedReports.computeIfAbsent(reportFrame, ignored -> new HashSet<>());
        Set<UUID> mismatches = new HashSet<>();
        frameReports.forEach((id, value) -> {
            if (!id.equals(referenceId) && participants.contains(id) && compared.add(id)
                    && value.longValue() != expected.longValue()) mismatches.add(id);
        });
        return new Comparison(mismatches.isEmpty() ? Result.MATCH : Result.MISMATCH,
                Set.copyOf(mismatches));
    }

    public record Comparison(Result result, Set<UUID> mismatches) {}

    public Result submit(
            UUID playerId,
            int reportEpoch,
            long reportFrame,
            long currentFrame,
            long digest,
            Set<UUID> participants
    ) {
        if (reportEpoch != epoch
                || reportFrame <= lastCompletedFrame
                || reportFrame <= 0
                || reportFrame % INTERVAL_FRAMES != 0
                || reportFrame > currentFrame
                || currentFrame - reportFrame > MAX_LAG_FRAMES
                || !participants.contains(playerId)) {
            return Result.REJECTED;
        }

        Map<UUID, Long> frameReports =
                reports.computeIfAbsent(reportFrame, ignored -> new HashMap<>());
        if (frameReports.putIfAbsent(playerId, digest) != null) {
            return Result.REJECTED;
        }

        long expected = frameReports.values().iterator().next();
        if (frameReports.values().stream().anyMatch(value -> value != expected)) {
            reports.remove(reportFrame);
            lastCompletedFrame = reportFrame;
            return Result.MISMATCH;
        }
        if (frameReports.keySet().containsAll(participants)) {
            reports.remove(reportFrame);
            lastCompletedFrame = reportFrame;
            reports.keySet().removeIf(frame -> frame < reportFrame);
            return Result.MATCH;
        }
        return Result.WAITING;
    }

    public enum Result {
        WAITING,
        MATCH,
        MISMATCH,
        REJECTED
    }
}
