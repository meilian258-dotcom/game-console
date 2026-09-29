package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.HashMap;

/** Pure upload ledger. Only server-verified missing objects may receive PUTs. */
public final class CabinetGameUploadPlan {
    private final CabinetGameManifest manifest;
    private final int missingMask;
    private final long missingBytes;
    private int file, offset;
    private boolean closed, finished;

    public CabinetGameUploadPlan(CabinetGameManifest manifest, int missingMask) {
        this.manifest = Objects.requireNonNull(manifest);
        if (missingMask < 0 || missingMask > allFilesMask(manifest.files().size()))
            throw new IllegalArgumentException("Invalid missing game mask");
        this.missingMask = missingMask;
        long bytes = 0;var objects = new HashMap<String, CabinetGameManifest.Entry>();
        for (int i = 0; i < manifest.files().size(); i++) {
            var entry = manifest.files().get(i);var previous = objects.putIfAbsent(entry.sha256(), entry);
            if (previous != null && (previous.size() != entry.size() || (missingMask & (1 << i)) != 0))
                throw new IllegalArgumentException("Inconsistent or repeated missing game object");
            if ((missingMask & (1 << i)) != 0) bytes += entry.size();
        }
        missingBytes = bytes;
        skipCached();
    }

    /** Full per-entry SHA/size checks precede this; upload equal hash/size aliases just once. */
    public static CabinetGameUploadPlan fromVerifiedMissing(CabinetGameManifest manifest, int perEntryMissingMask) {
        Objects.requireNonNull(manifest);
        if (perEntryMissingMask < 0 || perEntryMissingMask > allFilesMask(manifest.files().size()))
            throw new IllegalArgumentException("Invalid verified game mask");
        int uniqueMask = 0;var firstByHash = new HashMap<String, Integer>();
        for (int i = 0; i < manifest.files().size(); i++) {
            var entry = manifest.files().get(i);Integer previous = firstByHash.putIfAbsent(entry.sha256(), i);
            if (previous == null) uniqueMask |= perEntryMissingMask & (1 << i);
            else if (entry.size() != manifest.files().get(previous).size()
                    || ((perEntryMissingMask >>> i) & 1) != ((perEntryMissingMask >>> previous) & 1))
                throw new IllegalArgumentException("Inconsistent verified game object");
        }
        return new CabinetGameUploadPlan(manifest, uniqueMask);
    }

    public static int allFilesMask(int count) {
        if (count < 1 || count > CabinetGameManifest.MAX_FILES) throw new IllegalArgumentException("File count");
        return (1 << count) - 1;
    }

    public int missingMask() { return missingMask; }
    public long missingBytes() { return missingBytes; }
    public synchronized int nextFile() { return file; }
    public synchronized int offset() { return offset; }

    /** Validate before writing; call accepted only after append and any final SHA commit succeed. */
    public synchronized void validate(int requestedFile, int requestedOffset, int length) {
        if (closed || finished || file >= manifest.files().size() || requestedFile != file || requestedOffset != offset
                || length < 1 || length > CabinetGameManifest.CHUNK || length > manifest.files().get(file).size() - offset)
            throw new IllegalArgumentException("Upload range is not the next missing range");
    }

    public synchronized void accepted(int requestedFile, int requestedOffset, int length) {
        validate(requestedFile, requestedOffset, length);
        offset += length;
        if (offset == manifest.files().get(file).size()) {
            file++;
            offset = 0;
            skipCached();
        }
    }

    public synchronized void requireComplete() {
        if (closed || finished || file != manifest.files().size()) throw new IllegalStateException("Game upload is not complete");
    }

    /** Invoke only at END, after full object verification and fresh server authorization. */
    public synchronized void finish(Runnable commitBinding) {
        requireComplete();
        Objects.requireNonNull(commitBinding).run();
        finished = true;
    }

    public synchronized void close() { closed = true; }

    private void skipCached() {
        while (file < manifest.files().size() && (missingMask & (1 << file)) == 0) file++;
    }
}
