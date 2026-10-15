// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.home;

/** Non-persistent empty-hardware presentation. Never a session or an input entitlement. */
public final class EmptyConsolePower {
    private boolean on;

    public boolean turnOn(boolean hasCartridge, boolean connected, boolean televisionOn) {
        if (hasCartridge || !connected || !televisionOn || on) return false;
        on = true;
        return true;
    }

    public boolean reconcile(boolean hasCartridge, boolean connected, boolean televisionOn) {
        if (hasCartridge || !connected || !televisionOn) on = false;
        return on;
    }

    public void clear() { on = false; }
}
