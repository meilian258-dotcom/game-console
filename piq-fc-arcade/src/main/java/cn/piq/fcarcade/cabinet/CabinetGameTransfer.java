package cn.piq.fcarcade.cabinet;

/** Pure four-command transfer ledger. IO completion does not release a request permit. */
public final class CabinetGameTransfer {
    public static final int PIPELINE = 4;
    private final int[] sizes, offsets;
    private int nextCommand = 1, nextReply, pending = 1, lastFile = -1;
    private boolean opened, ending, closed;
    public CabinetGameTransfer(int[] sizes) {
        if (sizes == null || sizes.length < 1 || sizes.length > CabinetGameManifest.MAX_FILES) throw new IllegalArgumentException("File count");
        this.sizes = sizes.clone(); this.offsets = new int[sizes.length];
        long total = 0;
        for (int size : sizes) {
            if (size < 1 || size > CabinetGameManifest.MAX_FILE) throw new IllegalArgumentException("File size");
            total += size;
        }
        if (total > CabinetGameManifest.MAX_TOTAL) throw new IllegalArgumentException("Manifest size");
    }
    public synchronized boolean reserve(int sequence, boolean terminal) {
        if (closed || !opened || ending || sequence != nextCommand || pending >= PIPELINE) return false;
        nextCommand++; pending++; ending = terminal; return true;
    }
    /** Call only once a reply has entered the bounded, real-completion Connection transport. */
    public synchronized void delivered(int sequence) {
        if (closed || pending <= 0 || sequence != nextReply) throw new IllegalStateException("Reply order");
        nextReply++; pending--; if (sequence == 0) opened = true;
    }
    /** Skipping completely cached files is legal; backwards/repeated/partial-file ranges are not. */
    public synchronized int download(int file, int offset) {
        if (closed || file < 0 || file >= sizes.length || file < lastFile || offset != offsets[file]
                || offset < 0 || offset >= sizes[file]
                || (file > lastFile && lastFile >= 0 && offsets[lastFile] != sizes[lastFile]))
            throw new IllegalArgumentException("Download range is not sequential");
        int count = Math.min(CabinetGameManifest.CHUNK, sizes[file] - offset);
        offsets[file] += count; lastFile = file; return count;
    }
    public synchronized int pending() { return pending; }
    public synchronized void close() { closed = true; pending = 0; }
}
