package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.cabinet.CabinetNetplay;
import cn.piq.fcarcade.cabinet.CabinetRoomNetwork;
import cn.piq.fcarcade.cabinet.CabinetSyncMode;

/** Checks the actual transport before acquiring a client session. Never grants a seat. */
final class CabinetClientAdmission {
    private CabinetClientAdmission() {}

    static boolean accepts(CabinetRoomNetwork.Assignment assignment, CabinetRoomNetwork.NetplayStart grant) {
        if (CabinetBackends.maxPlayers(assignment.backend()) < assignment.capacity()) return false;
        if (assignment.mode() != CabinetSyncMode.LOCAL_SYNC) return true;
        // Netplay uses LOCAL_SYNC on the wire, but has a separate capability declaration.
        // Only the assignment delivered with this grant may use that declaration.
        boolean netplay = grant != null && grant.assignment() == assignment;
        int supported = netplay ? CabinetNetplay.maxPlayers(assignment.backend())
                : CabinetBackends.syncMaxPlayers(assignment.backend());
        return supported >= assignment.capacity();
    }
}
