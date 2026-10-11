package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.*;
import com.replaymod.replaystudio.replay.*;
import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Real codec + real ReplayFile interface, with an in-memory archive instead of Minecraft or native cores. */
public final class ReplayTrackLifecycleTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Archive recorded = new Archive(60);
        RecordingSession session = new RecordingSession(recorded.file);
        session.submit(frame("cabinet", 10, 0xff000001));
        session.submit(frame("cabinet", 25, 0xff000002));
        session.close("cabinet", 35);
        session.submit(frame("cabinet", 50, 0xff000003));
        session.finish();
        TrackIndex index = TrackFiles.index(recorded.file);
        check(index.frames().size() == 4 && index.chunks().size() == 1, "partial eight-frame chunk flushes at close");
        check(recorded.entries.size() == 3, "chunk, manifest and aggregate diagnostic entries");
        String recordedDiagnostic = new String(recorded.entries.get(TrackFiles.DIAGNOSTIC_ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        check(recordedDiagnostic.contains("result=complete") && recordedDiagnostic.contains("frames=4")
                && recordedDiagnostic.contains("images=3") && recordedDiagnostic.contains("sources=1"), "diagnostics count only committed frames and sources");
        check(!recordedDiagnostic.contains("cabinet") && !recordedDiagnostic.contains("minecraft:"), "diagnostics omit private source identity and dimension");
        var player = new PlaybackSession(recorded.file);
        check(player.prepareAt(26, 2_000), "async preparation ready");
        check(player.readyAt(26), "exact frame selection reports ready");
        check(player.at(26).size() == 1 && player.at(26).get(0).abgr()[0] == 0xff000002, "latest source frame");
        check(player.prepareAt(36, 2_000) && player.at(36).isEmpty(), "closed state removes source");
        check(player.prepareAt(11, 2_000) && player.at(11).get(0).abgr()[0] == 0xff000001, "backwards seek restores exact old frame");
        player.close(); check(player.at(50).isEmpty(), "closed player has no images");
        check(!player.readyAt(26), "closed player never reports ready");

        recorded.markers = Set.of(marker(20, TrackCutPlan.START_CUT), marker(40, TrackCutPlan.END_CUT));
        byte[] sourceManifest = recorded.entries.get(TrackIndex.INDEX).clone();
        Archive cut = new Archive(40);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(recorded.file); MarkerTrackBridge.beforeSave(cut.file); MarkerTrackBridge.end();
        TrackIndex cutIndex = TrackFiles.index(cut.file);
        check(cutIndex.frames().stream().anyMatch(r -> r.time() == 20), "cut boundary receives state seed");
        var cutPlayer = new PlaybackSession(cut.file);
        check(cutPlayer.prepareAt(20, 2_000) && cutPlayer.at(20).isEmpty(), "cut carry preserves a close, not stale image");
        check(cutPlayer.prepareAt(30, 2_000) && cutPlayer.at(30).get(0).abgr()[0] == 0xff000003, "post-cut clock remapped");
        cutPlayer.close();
        check(Arrays.equals(sourceManifest, recorded.entries.get(TrackIndex.INDEX)), "input track remains unchanged");

        recorded.markers = Set.of(marker(30, TrackCutPlan.SPLIT));
        Archive first = new Archive(29), second = new Archive(30);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(recorded.file);
        MarkerTrackBridge.beforeSave(first.file); MarkerTrackBridge.beforeSave(second.file); MarkerTrackBridge.end();
        check(TrackFiles.index(first.file).frames().size() == 2, "split first actual output");
        var splitPlayer = new PlaybackSession(second.file);
        check(splitPlayer.prepareAt(0, 2_000) && splitPlayer.at(0).get(0).abgr()[0] == 0xff000002, "split begins with prior frame state");
        check(splitPlayer.prepareAt(5, 2_000) && splitPlayer.at(5).isEmpty(), "split close remapped"); splitPlayer.close();

        Archive empty = new Archive(10);
        var emptyPlayer = new PlaybackSession(empty.file);
        check(emptyPlayer.prepareAt(10, 2_000) && emptyPlayer.at(10).isEmpty(), "old recordings without track are harmless"); emptyPlayer.close();

        Archive tour = new Archive(4000);
        var tourWriter = new TrackFiles.Writer(tour.file);
        var tourBatch = new ArrayList<ScreenFrame>();
        for (int n = 0; n < 32; n++) {
            tourBatch.add(frame("cabinet-" + n, n * 100, n));
            tourBatch.add(ScreenFrame.closed("cabinet-" + n, "minecraft:overworld", n * 100 + 50));
            if (tourBatch.size() == 8) { tourWriter.append(tourBatch); tourBatch.clear(); }
        }
        tourWriter.finish();
        TrackIndex tourIndex = TrackFiles.index(tour.file);
        check(tourIndex.frames().size() == 64 && tourIndex.select(1701).get(0).source().equals("cabinet-17"), "walk past seventeen cabinets without stopping recording");
        check(tourIndex.select(4000).isEmpty(), "all visited cabinets close");

        Archive pressured = new Archive(1000);
        pressured.writeEntered = new CountDownLatch(1); pressured.releaseWrite = new CountDownLatch(1);
        var pressuredSession = new RecordingSession(pressured.file);
        for (int n = 0; n < 8; n++) pressuredSession.submit(frame("busy", n, n));
        check(pressured.writeEntered.await(2, TimeUnit.SECONDS), "writer gate reached");
        for (int n = 8; n < 40; n++) pressuredSession.submit(frame("busy", n, n));
        pressuredSession.close("busy", 100);
        pressured.releaseWrite.countDown(); pressuredSession.finish();
        TrackIndex pressureIndex = TrackFiles.index(pressured.file);
        check(pressureIndex.frames().stream().anyMatch(ref -> ref.source().equals("busy") && ref.closed() && ref.time() == 100), "close survives full pixel queue");
        check(pressureIndex.select(100).isEmpty(), "no residual image after backpressure close");
        check(new String(pressured.entries.get(TrackFiles.DIAGNOSTIC_ENTRY), java.nio.charset.StandardCharsets.UTF_8).contains("result=partial_capture"), "backpressure persists a partial-capture outcome");
        check(pressured.entries.containsKey(TrackFiles.WARNING_ENTRY), "partial capture has a replay-visible warning");

        Archive emptyRecorded = new Archive(10);
        new RecordingSession(emptyRecorded.file).finish();
        check(emptyRecorded.entries.size() == 2 && !emptyRecorded.entries.containsKey(TrackIndex.INDEX), "empty capture writes warning and diagnostic without inventing frames");
        check(new String(emptyRecorded.entries.get(TrackFiles.DIAGNOSTIC_ENTRY), java.nio.charset.StandardCharsets.UTF_8).contains("result=empty_capture"), "empty capture distinguishable from unpatched old replay");
        check(emptyRecorded.entries.values().stream().allMatch(bytes -> bytes.length <= 4096), "empty capture diagnostics bounded");
        TrackFiles.index(emptyRecorded.file);
        check(ReplayBridge.status().equals(TrackFiles.EMPTY_WARNING), "reopening empty capture exposes its actionable reason");
        emptyRecorded.markers = Set.of(marker(5, TrackCutPlan.SPLIT));
        Archive emptyFirst = new Archive(4), emptySecond = new Archive(5);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(emptyRecorded.file);
        MarkerTrackBridge.beforeSave(emptyFirst.file); MarkerTrackBridge.beforeSave(emptySecond.file); MarkerTrackBridge.end();
        check(Arrays.equals(emptyRecorded.entries.get(TrackFiles.WARNING_ENTRY), emptyFirst.entries.get(TrackFiles.WARNING_ENTRY))
                && Arrays.equals(emptyRecorded.entries.get(TrackFiles.WARNING_ENTRY), emptySecond.entries.get(TrackFiles.WARNING_ENTRY)), "all empty split outputs preserve the capture warning");
        check(Arrays.equals(emptyRecorded.entries.get(TrackFiles.DIAGNOSTIC_ENTRY), emptyFirst.entries.get(TrackFiles.DIAGNOSTIC_ENTRY))
                && Arrays.equals(emptyRecorded.entries.get(TrackFiles.DIAGNOSTIC_ENTRY), emptySecond.entries.get(TrackFiles.DIAGNOSTIC_ENTRY)), "all empty split outputs preserve aggregate diagnostics");
        check(!emptyFirst.entries.containsKey(TrackIndex.INDEX) && !emptySecond.entries.containsKey(TrackIndex.INDEX), "empty splits still invent no frames");
        Archive oldWithoutInfo = new Archive(10), oldOutput = new Archive(10);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(oldWithoutInfo.file); MarkerTrackBridge.beforeSave(oldOutput.file); MarkerTrackBridge.end();
        check(oldOutput.entries.isEmpty(), "old recordings are not incorrectly relabelled as patched empty captures");
        Archive oversizedInfo = new Archive(10), rejectedInfo = new Archive(10);
        oversizedInfo.entries.put(TrackFiles.DIAGNOSTIC_ENTRY, new byte[4097]);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(oversizedInfo.file); MarkerTrackBridge.beforeSave(rejectedInfo.file); MarkerTrackBridge.end();
        check(!rejectedInfo.entries.containsKey(TrackFiles.DIAGNOSTIC_ENTRY) && rejectedInfo.entries.get(TrackFiles.WARNING_ENTRY).length <= 4096, "oversized input diagnostics rejected with bounded failure notice");

        Archive failedFinalize = new Archive(10);
        var failedWriter = new TrackFiles.Writer(failedFinalize.file);
        failedWriter.append(List.of(frame("hidden-identity", 1, 42)));
        failedFinalize.failWrite = TrackFiles.DIAGNOSTIC_ENTRY;
        boolean failedOnce = false, failedAgain = false;
        try { failedWriter.finish(1, 0, "none"); } catch (IOException expected) { failedOnce = true; }
        try { failedWriter.finish(1, 0, "none"); } catch (IOException expected) { failedAgain = true; }
        check(failedOnce && failedAgain, "failed finalization never returns a successful summary, including retry");

        Archive splitExpiry = new Archive(2000);
        var expiryWriter = new TrackFiles.Writer(splitExpiry.file);
        expiryWriter.append(List.of(frame("short", 0, 7))); expiryWriter.finish();
        splitExpiry.markers = Set.of(marker(1500, TrackCutPlan.SPLIT));
        Archive expiryFirst = new Archive(1499), expirySecond = new Archive(500);
        MarkerTrackBridge.begin(); MarkerTrackBridge.input(splitExpiry.file);
        MarkerTrackBridge.beforeSave(expiryFirst.file); MarkerTrackBridge.beforeSave(expirySecond.file); MarkerTrackBridge.end();
        var expiryIndex = TrackFiles.index(expirySecond.file);
        check(!expiryIndex.select(499).isEmpty() && expiryIndex.select(500).isEmpty(), "split seed preserves remaining TTL rather than restarting it");

        Archive loading = new Archive(60); loading.entries.putAll(recorded.entries);
        loading.readEntered = new CountDownLatch(1); loading.releaseRead = new CountDownLatch(1);
        var loadingPlayer = new PlaybackSession(loading.file);
        check(!loadingPlayer.readyAt(26), "pending index is not ready, distinct from an empty screen set");
        check(loading.readEntered.await(2, TimeUnit.SECONDS) && !loadingPlayer.readyAt(26), "nonblocking ready query while archive IO is stalled");
        loading.releaseRead.countDown();
        check(loadingPlayer.prepareAt(26, 2_000) && loadingPlayer.readyAt(26), "ready becomes true after exact snapshot decode");
        Field playbackField = ReplayBridge.class.getDeclaredField("playback"); playbackField.setAccessible(true);
        playbackField.set(null, loadingPlayer);
        ReplayBridge.forcefullyStopPlayback(); ReplayBridge.forcefullyStopPlayback();
        check(playbackField.get(null) == null && !loadingPlayer.readyAt(26) && !ReplayBridge.playing(), "forced stop idempotently clears playback and decoder");
        System.out.println("ReplayTrackLifecycleTest: " + checks + " checks passed");
    }
    private static ScreenFrame frame(String source, long time, int pixel) {
        return new ScreenFrame(source, "minecraft:overworld", time, 1, 1, new int[]{pixel}, List.of(
            new ScreenVertex(0,0,0,0,0,-1), new ScreenVertex(1,0,0,1,0,-1),
            new ScreenVertex(1,1,0,1,1,-1), new ScreenVertex(0,1,0,0,1,-1)));
    }
    private static Marker marker(int time, String name) { Marker marker = new Marker(); marker.setTime(time); marker.setName(name); return marker; }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static final class Archive implements InvocationHandler {
        final Map<String, byte[]> entries = new HashMap<>();
        final ReplayMetaData metadata = new ReplayMetaData();
        Set<Marker> markers = Set.of();
        volatile CountDownLatch writeEntered, releaseWrite;
        volatile CountDownLatch readEntered, releaseRead;
        String failWrite;
        final ReplayFile file;
        Archive(int duration) { metadata.setDuration(duration); file = (ReplayFile) Proxy.newProxyInstance(ReplayFile.class.getClassLoader(), new Class<?>[]{ReplayFile.class}, this); }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "get" -> {
                    if (readEntered != null) {
                        readEntered.countDown();
                        if (!releaseRead.await(5, TimeUnit.SECONDS)) throw new IOException("Test reader gate timeout");
                    }
                    yield Optional.fromNullable(entries.containsKey((String) args[0]) ? new ByteArrayInputStream(entries.get((String) args[0])) : null);
                }
                case "write" -> {
                    if (Objects.equals(failWrite, args[0])) throw new IOException("Injected test archive write failure");
                    if (((String) args[0]).contains("/chunks/") && writeEntered != null) {
                        writeEntered.countDown();
                        if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IOException("Test writer gate timeout");
                    }
                    yield new ByteArrayOutputStream() { @Override public void close() { entries.put((String) args[0], toByteArray()); } };
                }
                case "getMetaData" -> metadata;
                case "getMarkers" -> Optional.of(markers);
                case "save", "close" -> null;
                case "toString" -> "InMemoryReplay";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }
}
