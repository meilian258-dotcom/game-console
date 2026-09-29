package cn.piq.flashbox.runtime;

/** UI visibility is not the lifetime of an already-running local television session. */
public final class PlaybackPolicy {
    private PlaybackPolicy() {}
    public static boolean keepAfterMenuClose(boolean starting, boolean ready) { return !starting && ready; }
    public static boolean pause(boolean windowActive, boolean otherMenu, boolean gamePaused) {
        return !windowActive || otherMenu || gamePaused;
    }
}
