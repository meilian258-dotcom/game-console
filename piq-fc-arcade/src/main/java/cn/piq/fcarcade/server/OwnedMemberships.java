package cn.piq.fcarcade.server;

import java.util.Map;

final class OwnedMemberships {
    private OwnedMemberships() {
    }

    static <P, K> void leaveOtherBeforeTransfer(
            Map<P, K> memberships, P playerId, K target, Runnable leaveCurrent
    ) {
        K previous = memberships.get(playerId);
        if (previous == null || previous.equals(target)) return;
        leaveCurrent.run();
        if (memberships.containsKey(playerId)) {
            throw new IllegalStateException("Previous cabinet membership was not released");
        }
    }

    static <P, K> boolean removeIfOwnedBy(
            Map<P, K> memberships,
            P playerId,
            K sessionKey
    ) {
        return memberships.remove(playerId, sessionKey);
    }
}
