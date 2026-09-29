package cn.piq.fcarcade.server;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only catalog recognition; never rewrites a persisted key or migrates a save. */
final class PlayerSaveCatalogKey {
    private static final String PLAYER_MEDIA = "player-home-v1|";
    private static final String SERVER_MEDIA = "server-home-v1|";
    private static final String ZAPPER = NesCoreVariant.ZAPPER_V1.saveKey("");
    private static final String MAPPER19 = NesCoreVariant.MAPPER19_V1.saveKey("");
    private static final String LIBRETRO = NesCoreVariant.LIBRETRO_V1.saveKey("");
    private static final String LIBRETRO_ZAPPER = NesCoreVariant.LIBRETRO_ZAPPER_V1.saveKey("");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private PlayerSaveCatalogKey() {}

    /** Returns the recognized player portion, or empty for a non-player/unknown key. */
    static String normalize(String key) {
        if (key == null) return "";
        String player = key;
        // Prefixes are ordered and may occur only once; do not scan for embedded player text.
        if (player.startsWith(PLAYER_MEDIA)) player = player.substring(PLAYER_MEDIA.length());
        else if (player.startsWith(SERVER_MEDIA)) player = player.substring(SERVER_MEDIA.length());
        if (player.startsWith(ZAPPER)) player = player.substring(ZAPPER.length());
        else if (player.startsWith(MAPPER19)) player = player.substring(MAPPER19.length());
        else if (player.startsWith(LIBRETRO)) player = player.substring(LIBRETRO.length());
        else if (player.startsWith(LIBRETRO_ZAPPER)) player = player.substring(LIBRETRO_ZAPPER.length());
        else if(player.startsWith(cn.piq.fcarcade.netplay.FcNetplaySaves.prefix(false)))player=player.substring(cn.piq.fcarcade.netplay.FcNetplaySaves.prefix(false).length());
        else if(player.startsWith(cn.piq.fcarcade.netplay.FcNetplaySaves.prefix(true)))player=player.substring(cn.piq.fcarcade.netplay.FcNetplaySaves.prefix(true).length());
        else if(player.startsWith(cn.piq.fcarcade.netplay.FcNetplaySaves.jniPrefix()))player=player.substring(cn.piq.fcarcade.netplay.FcNetplaySaves.jniPrefix().length());

        String[] parts = player.split("\\|", -1);
        if (parts.length < 2 || !parts[0].equals("player") || !canonicalUuid(parts[1])) return "";
        if (parts.length == 2) return player; // Original per-ROM personal saves.
        if (parts.length == 4 && (parts[2].equals("slot") || parts[2].equals("global-slot"))
                && (parts[3].equals("1") || parts[3].equals("2") || parts[3].equals("3"))) return player;
        // Alpha28 light-gun saves qualified their machine key with the host UUID.
        if (parts.length == 5 && DIMENSION.matcher(parts[2]).matches()
                && canonicalCoordinates(parts[3]) && parts[4].equals("LOCKSTEP")) return player;
        return "";
    }

    private static boolean canonicalUuid(String text) {
        if (text.length() != 36) return false;
        try { return UUID.fromString(text).toString().equalsIgnoreCase(text); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean canonicalCoordinates(String text) {
        String[] values = text.split(",", -1);
        if (values.length != 3) return false;
        try {
            for (String value : values)
                if (!Integer.toString(Integer.parseInt(value)).equals(value)) return false;
            return true;
        } catch (NumberFormatException invalid) { return false; }
    }
}
