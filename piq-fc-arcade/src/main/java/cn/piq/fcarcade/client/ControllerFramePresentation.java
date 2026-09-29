package cn.piq.fcarcade.client;

/** Only completed core frames contribute pulses; a render drains each pulse once. */
final class ControllerFramePresentation {
    private long revision;
    private int latest, pending, presented;
    synchronized long revision() { return revision; }
    synchronized void complete(long expectedRevision, int one, int two) {
        if (revision != expectedRevision) return;
        latest = (one & 255) | ((two & 255) << 8);
        pending |= latest;
    }
    synchronized void present() { presented = latest | pending; pending = 0; }
    synchronized int latest(int port) { return valid(port) ? (latest >>> (port * 8)) & 255 : 0; }
    synchronized int presented(int port) { return valid(port) ? (presented >>> (port * 8)) & 255 : 0; }
    synchronized void clear() { revision++; latest = pending = presented = 0; }
    private static boolean valid(int port) { return port >= 0 && port < 2; }
}
