package cn.piq.fcarcade.client;

/** Main-thread lifecycle gate. Resuming cannot re-play a key held through a GUI/reset. */
final class ControllerInputCapture {
    private boolean active, waitForNeutral;
    int sample(int currentMask) {
        active = true;
        if (waitForNeutral) {
            if (currentMask == 0) waitForNeutral = false;
            return 0;
        }
        return currentMask;
    }
    boolean suspend() {
        boolean hadInputOwnership = active;
        active = false;
        waitForNeutral = true;
        return hadInputOwnership;
    }
    boolean armed() { return active && !waitForNeutral; }
}
