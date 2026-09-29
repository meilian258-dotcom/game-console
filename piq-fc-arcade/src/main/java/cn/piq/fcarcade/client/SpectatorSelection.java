package cn.piq.fcarcade.client;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Pure local admission policy; controllers never consume a spectator slot. */
final class SpectatorSelection {
    static final double RETENTION_DISTANCE_BLOCKS = 2.0D;

    private SpectatorSelection() {
    }

    /** One shared native slot: an operator takes precedence; passive JNI screens share one slot. */
    static Set<Long> selectWithJni(Collection<Candidate> candidates,Set<Long> previous,
                                   int maximumSpectators,Set<Long> jniSessions) {
        boolean operator=candidates.stream().anyMatch(c->c.controller()&&jniSessions.contains(c.sessionId()));
        Long spectator=operator?null:candidates.stream().filter(c->!c.controller()&&jniSessions.contains(c.sessionId()))
                .filter(c->Double.isFinite(c.distanceSquared())&&c.distanceSquared()>=0)
                .min(Comparator.comparingDouble((Candidate c)->priorityDistance(c,previous)).thenComparingLong(Candidate::sessionId))
                .map(Candidate::sessionId).orElse(null);
        return select(candidates.stream().filter(c->c.controller()||!jniSessions.contains(c.sessionId())
                ||java.util.Objects.equals(c.sessionId(),spectator)).toList(),previous,maximumSpectators);
    }

    static Set<Long> select(
            Collection<Candidate> candidates,
            Set<Long> previous,
            int maximumSpectators
    ) {
        if (maximumSpectators < 0 || maximumSpectators > 8) {
            throw new IllegalArgumentException("Spectator limit must be 0..8");
        }
        Set<Long> selected = new HashSet<>();
        candidates.stream()
                .filter(Candidate::controller)
                .map(Candidate::sessionId)
                .forEach(selected::add);
        candidates.stream()
                .filter(candidate -> !candidate.controller())
                .filter(candidate -> Double.isFinite(candidate.distanceSquared())
                        && candidate.distanceSquared() >= 0.0D)
                .sorted(Comparator
                        .comparingDouble((Candidate candidate) -> priorityDistance(
                                candidate, previous))
                        .thenComparingLong(Candidate::sessionId))
                .limit(maximumSpectators)
                .map(Candidate::sessionId)
                .forEach(selected::add);
        return Set.copyOf(selected);
    }

    private static double priorityDistance(Candidate candidate, Set<Long> previous) {
        double distance = Math.sqrt(candidate.distanceSquared());
        // A challenger must be about two blocks nearer before replacing an
        // admitted spectator; walking across a distance tie cannot thrash cores.
        return distance - (previous.contains(candidate.sessionId())
                ? RETENTION_DISTANCE_BLOCKS : 0.0D);
    }

    record Candidate(long sessionId, boolean controller, double distanceSquared) {
    }
}
