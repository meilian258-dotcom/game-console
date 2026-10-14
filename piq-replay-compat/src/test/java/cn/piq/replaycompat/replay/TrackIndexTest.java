package cn.piq.replaycompat.replay;

import java.io.*;
import java.util.*;

public final class TrackIndexTest {
    public static void main(String[] args) throws Exception {
        var rows = List.of(new TrackIndex.Ref("a", 10, 0, 0), new TrackIndex.Ref("b", 20, 0, 1), new TrackIndex.Ref("a", 30, 0, 2), new TrackIndex.Ref("a", 30, 0, 3));
        var index = new TrackIndex(rows, List.of(new TrackIndex.Chunk(0, 50)));
        check(index.select(0).isEmpty(), "before first frame");
        check(index.select(15).equals(List.of(rows.get(0))), "one source");
        check(index.select(30).contains(rows.get(3)) && !index.select(30).contains(rows.get(2)), "same-time last event wins");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); index.write(out);
        var decoded = TrackIndex.read(new ByteArrayInputStream(out.toByteArray()));
        check(decoded.frames().equals(rows) && decoded.select(25).size() == 2, "roundtrip and independent sources");
        byte[] truncated = Arrays.copyOf(out.toByteArray(), out.size() - 1);
        reject(() -> TrackIndex.read(new ByteArrayInputStream(truncated)), "truncated");
        reject(() -> new TrackIndex(List.of(rows.get(0), rows.get(0)), index.chunks()), "duplicate index slot");
        reject(() -> new TrackIndex(List.of(new TrackIndex.Ref("a", 0, 1, 0)), index.chunks()), "missing chunk");
        reject(() -> TrackIndex.entry(-1), "path escape via negative index");
        check(TrackIndex.entry(7).equals("piq-screen/v1/chunks/00007.bin"), "fixed namespace");
        check(index.select(2030).isEmpty(), "TTL bounds stale screens");
        var many = new ArrayList<TrackIndex.Ref>();
        var manyChunks = new ArrayList<TrackIndex.Chunk>();
        for (int n = 0; n < 32; n++) { if (n % 8 == 0) manyChunks.add(new TrackIndex.Chunk(n / 8, 50)); many.add(new TrackIndex.Ref("source-" + n, n, n / 8, n % 8)); }
        var manyIndex = new TrackIndex(many, manyChunks);
        check(manyIndex.select(100).size() == 16, "more than sixteen lifetime sources, bounded malicious active snapshot");
        check(manyIndex.select(100).get(0).source().equals("source-31"), "latest active sources win budget");
        var closed = new TrackIndex(List.of(new TrackIndex.Ref("a", 10, 0, 0, true, 10)), index.chunks());
        check(closed.select(10).isEmpty(), "closed state omitted before decoding");
        reject(() -> new TrackIndex(List.of(new TrackIndex.Ref("a", 10, 0, 0, false, 2011)), index.chunks()), "excess expiry");
        System.out.println("TrackIndexTest: 14 checks passed");
    }
    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing run, String label) throws Exception { try { run.run(); throw new AssertionError(label); } catch (IOException | IllegalArgumentException expected) { } }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
