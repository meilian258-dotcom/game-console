package cn.piq.fcarcade.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SessionRoster {
    private final LinkedHashMap<UUID, ArcadeRole> members = new LinkedHashMap<>();

    public ArcadeRole join(UUID playerId) {
        if (!members.containsKey(playerId)) {
            members.put(playerId, ArcadeRole.SPECTATOR);
            normalizeRoles();
        }
        return members.get(playerId);
    }

    public ArcadeRole rejoin(UUID playerId, ArcadeRole previousRole) {
        if (members.containsKey(playerId)) return members.get(playerId);
        if (previousRole == ArcadeRole.PLAYER_ONE) {
            LinkedHashMap<UUID, ArcadeRole> reordered = new LinkedHashMap<>();
            reordered.put(playerId, ArcadeRole.SPECTATOR);
            reordered.putAll(members);
            members.clear();
            members.putAll(reordered);
        } else {
            members.put(playerId, ArcadeRole.SPECTATOR);
        }
        normalizeRoles();
        return members.get(playerId);
    }

    public boolean remove(UUID playerId) {
        if (members.remove(playerId) == null) return false;
        normalizeRoles();
        return true;
    }

    public ArcadeRole roleOf(UUID playerId) {
        return members.get(playerId);
    }

    /** Fixed appliance sockets never promote another player on removal. */
    public boolean joinAt(UUID playerId,int port){
        if(playerId==null||port<0||port>1||members.containsKey(playerId))return false;
        ArcadeRole role=port==0?ArcadeRole.PLAYER_ONE:ArcadeRole.PLAYER_TWO;
        if(members.containsValue(role))return false;members.put(playerId,role);return true;
    }
    public boolean removeFixed(UUID playerId){return members.remove(playerId)!=null;}

    public boolean contains(UUID playerId) {
        return members.containsKey(playerId);
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public int size() {
        return members.size();
    }

    public List<UUID> playerIds() {
        return List.copyOf(members.keySet());
    }

    public Map<UUID, ArcadeRole> snapshot() {
        return Map.copyOf(members);
    }

    private void normalizeRoles() {
        List<UUID> orderedPlayers = new ArrayList<>(members.keySet());
        for (int index = 0; index < orderedPlayers.size(); index++) {
            ArcadeRole role = switch (index) {
                case 0 -> ArcadeRole.PLAYER_ONE;
                case 1 -> ArcadeRole.PLAYER_TWO;
                default -> ArcadeRole.SPECTATOR;
            };
            members.put(orderedPlayers.get(index), role);
        }
    }
}
