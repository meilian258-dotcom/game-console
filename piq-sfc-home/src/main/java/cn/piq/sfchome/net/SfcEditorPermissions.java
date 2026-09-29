package cn.piq.sfchome.net;

import cn.piq.fcarcade.access.PlayerContentPolicy;

/** Pure presentation/codec guard. Server operations still consult current player access. */
public final class SfcEditorPermissions {
    private static final int KNOWN = PlayerContentPolicy.BROWSE | PlayerContentPolicy.ROM_UPLOAD
            | PlayerContentPolicy.COVER_UPLOAD | PlayerContentPolicy.ADMIN | PlayerContentPolicy.SERVER_ROM_USE | PlayerContentPolicy.SERVER_COVER_USE;
    private SfcEditorPermissions() {}
    public static int checked(int capabilities) {
        if (capabilities < 0 || (capabilities & ~KNOWN) != 0) throw new IllegalArgumentException("Invalid editor capabilities");
        return capabilities;
    }
    public static boolean browse(int capabilities) { return (checked(capabilities) & PlayerContentPolicy.BROWSE) != 0; }
    public static boolean upload(int capabilities, boolean cover) {
        return browse(capabilities) && (capabilities & (cover ? PlayerContentPolicy.COVER_UPLOAD : PlayerContentPolicy.ROM_UPLOAD)) != 0;
    }
    public static boolean admin(int capabilities) { return browse(capabilities) && (capabilities & PlayerContentPolicy.ADMIN) != 0; }
    public static boolean selection(int capabilities, boolean local, boolean cover) {
        return local ? upload(capabilities, cover) : browse(capabilities)
                && (capabilities & (cover ? PlayerContentPolicy.SERVER_COVER_USE : PlayerContentPolicy.SERVER_ROM_USE)) != 0;
    }
}
