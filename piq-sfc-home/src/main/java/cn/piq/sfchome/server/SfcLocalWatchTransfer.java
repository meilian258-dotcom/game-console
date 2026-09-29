package cn.piq.sfchome.server;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded, ordered, digest-checked snapshot assembly, independent from controller repairs. */
public final class SfcLocalWatchTransfer {
    private final int total;
    private final String sha;
    private final byte[] bytes;
    private final MessageDigest digest;
    private int received;
    private boolean failed;

    public SfcLocalWatchTransfer(int total, String sha) {
        if (total < 1 || total > SfcRepairLedger.MAX_STATE || !SfcRepairLedger.hash(sha))
            throw new IllegalArgumentException("Invalid observer snapshot");
        this.total = total; this.sha = sha; bytes = new byte[total];
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    public boolean append(int total, int offset, String sha, byte[] part) {
        if (failed || received == this.total || total != this.total || offset != received
                || !Objects.equals(this.sha, sha) || part == null || part.length < 1
                || part.length > SfcRepairLedger.CHUNK || (long) offset + part.length > total) {
            failed = true; return false;
        }
        System.arraycopy(part, 0, bytes, offset, part.length); digest.update(part); received += part.length;
        if (received == total && !HexFormat.of().formatHex(digest.digest()).equals(sha)) failed = true;
        return !failed;
    }
    public boolean complete() { return !failed && received == total; }
    /** Ownership transfers to the bounded transport/worker; no additional full-state copy. */
    public byte[] take() { if (!complete()) throw new IllegalStateException("Incomplete observer snapshot"); return bytes; }
    public int total() { return total; }
}
