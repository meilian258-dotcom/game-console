package cn.piq.fcarcade.access;

import java.util.Set;
import java.util.UUID;

/** Pure policy shared by FC and SFC. Browsing is not unrestricted file download authority. */
public record PlayerContentPolicy(boolean allPlayers, boolean romUploads, boolean coverUploads,
                                  Set<UUID> allowedPlayers, boolean serverRomUse, boolean serverCoverUse) {
    public static final int BROWSE = 1, ROM_UPLOAD = 2, COVER_UPLOAD = 4, ADMIN = 8;
    public static final int SERVER_ROM_USE = 16, SERVER_COVER_USE = 32;
    public static final int ALL = BROWSE | ROM_UPLOAD | COVER_UPLOAD | ADMIN | SERVER_ROM_USE | SERVER_COVER_USE;
    public static final int MAX_PLAYERS = 1024;
    /** Old explicit access grants retain existing-ROM use; new cover-library use stays off. */
    public PlayerContentPolicy(boolean allPlayers, boolean romUploads, boolean coverUploads, Set<UUID> allowedPlayers) {
        this(allPlayers, romUploads, coverUploads, allowedPlayers, true, false);
    }
    public PlayerContentPolicy {
        allowedPlayers = Set.copyOf(allowedPlayers);
        if (allowedPlayers.size() > MAX_PLAYERS) throw new IllegalArgumentException("授权名单最多 1024 人");
    }
    public int capabilities(UUID player, boolean operator) {
        if (player == null) return 0;
        if (operator) return ALL;
        if (!allPlayers && !allowedPlayers.contains(player)) return 0;
        return BROWSE | (romUploads ? ROM_UPLOAD : 0) | (coverUploads ? COVER_UPLOAD : 0)
                | (serverRomUse ? SERVER_ROM_USE : 0) | (serverCoverUse ? SERVER_COVER_USE : 0);
    }
    public static boolean has(int capabilities, int permission) {
        return permission != 0 && (capabilities & permission) == permission;
    }
    public static UUID parsePlayer(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("请输入完整玩家 UUID");
        UUID id = UUID.fromString(value);
        if (id.equals(new UUID(0, 0))) throw new IllegalArgumentException("玩家 UUID 无效");
        return id;
    }
}
