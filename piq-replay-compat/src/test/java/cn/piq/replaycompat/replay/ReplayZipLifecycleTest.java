package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenVertex;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ZipReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipFile;

/** Uses the pinned ReplayStudio ZIP implementation, not an in-memory ReplayFile substitute. */
public final class ReplayZipLifecycleTest {
    private static int checks;
    private static final byte[] ORIGINAL = {1, 3, 5, 7};
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("piq-replay-zip-test-").toRealPath();
        try {
            Path empty = directory.resolve("empty.mcpr");
            Object emptyOwner = new Object();
            try (ReplayFile file = create(empty)) {
                ReplayBridge.beginRecording(emptyOwner, () -> 100, file);
                check(ReplayBridge.recording(), "first recording is active");
                ReplayBridge.stopRecording(emptyOwner);
                check(!ReplayBridge.recording(), "stop immediately closes capture gate");
                ReplayBridge.finishRecording(emptyOwner);
                file.save();
            }
            try (ZipFile zip = new ZipFile(empty.toFile())) {
                check(zip.getEntry(TrackFiles.WARNING_ENTRY) != null && zip.getEntry(TrackFiles.DIAGNOSTIC_ENTRY) != null,
                        "real MCPR contains empty-capture warning and diagnostic after save/close");
                check(zip.getEntry(TrackIndex.INDEX) == null, "empty real MCPR invents no screen index");
                check(java.util.Arrays.equals(ORIGINAL, zip.getInputStream(zip.getEntry("existing-data.bin")).readAllBytes()), "normal ReplayFile entries are preserved");
            }
            try (ReplayFile file = new ZipReplayFile(new ReplayStudio(), empty.toFile())) {
                check(TrackFiles.index(file).frames().isEmpty() && ReplayBridge.status().equals(TrackFiles.EMPTY_WARNING), "real ZIP reopen retains empty-capture reason");
                check(read(file, TrackFiles.DIAGNOSTIC_ENTRY).contains("result=empty_capture"), "empty diagnostic is persisted UTF-8");
                MarkerTrackBridge.begin(); MarkerTrackBridge.input(file);
                try {
                    for (String name : List.of("empty-cut.mcpr", "empty-split.mcpr")) {
                        Path output = directory.resolve(name);
                        try (ReplayFile edited = create(output)) { MarkerTrackBridge.beforeSave(edited); edited.save(); }
                        try (ReplayFile edited = new ZipReplayFile(new ReplayStudio(), output.toFile())) {
                            check(read(edited, TrackFiles.WARNING_ENTRY).equals(TrackFiles.EMPTY_WARNING), "empty edited ZIP keeps actionable warning");
                            check(read(edited, TrackFiles.DIAGNOSTIC_ENTRY).contains("result=empty_capture")
                                    && !edited.get(TrackIndex.INDEX).isPresent(), "empty edited ZIP keeps diagnostic without inventing frames");
                        }
                    }
                } finally { MarkerTrackBridge.end(); }
            }

            ReplayBridge.forcefullyStopPlayback();
            Path recorded = directory.resolve("later.mcpr");
            Object laterOwner = new Object();
            try (ReplayFile file = create(recorded)) {
                ReplayBridge.beginRecording(laterOwner, () -> 200, file);
                ReplayBridge.finishRecording(emptyOwner);
                check(ReplayBridge.recording() && ReplayBridge.recordingTime() == 200, "late prior-owner finish cannot stop a later recording");
                ReplayBridge.submit(frame(200, 0xff123456));
                check(ReplayBridge.status().equals("已采集屏幕画面，正在录制"), "first accepted frame advances player-facing status");
                ReplayBridge.submit(frame(250, 0xff654321));
                ReplayBridge.stopRecording(laterOwner);
                ReplayBridge.submit(frame(300, 0xff000000));
                ReplayBridge.finishRecording(laterOwner);
                ReplayBridge.finishRecording(laterOwner);
                check(!ReplayBridge.recording(), "later finish is idempotent and releases active recorder");
                file.save();
            }
            try (ReplayFile file = new ZipReplayFile(new ReplayStudio(), recorded.toFile())) {
                TrackIndex index = TrackFiles.index(file);
                check(index.frames().size() == 2 && index.chunks().size() == 1, "real ZIP flushes final partial batch and ignores after-stop input");
                check(read(file, TrackFiles.DIAGNOSTIC_ENTRY).contains("result=complete"), "later recording has independent complete diagnostic");
                check(!file.get(TrackFiles.WARNING_ENTRY).isPresent(), "prior empty warning does not leak into later recording");
                var player = new PlaybackSession(file);
                try {
                    check(player.prepareAt(251, 2000) && player.at(251).getFirst().abgr()[0] == 0xff654321, "actual ZIP decoder selects saved later frame");
                    check(player.prepareAt(201, 2000) && player.at(201).getFirst().abgr()[0] == 0xff123456, "actual ZIP backward seek returns earlier saved pixels");
                } finally { player.close(); }
            }
            System.out.println("ReplayZipLifecycleTest: " + checks + " checks passed");
        } finally {
            // Delete only the fresh test-owned directory and its entries, never game data.
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) throw new IllegalStateException("Test cleanup escaped its directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
    private static ReplayFile create(Path output) throws Exception {
        ReplayFile file = new ZipReplayFile(new ReplayStudio(), null, output.toFile());
        try (var entry = file.write("existing-data.bin")) { entry.write(ORIGINAL); }
        return file;
    }
    private static String read(ReplayFile file, String entry) throws Exception {
        try (InputStream stream = file.get(entry).get()) { return new String(stream.readNBytes(4097), StandardCharsets.UTF_8); }
    }
    private static ScreenFrame frame(long time, int pixel) {
        return new ScreenFrame("test:world/screen", "minecraft:overworld", time, 1, 1, new int[]{pixel}, List.of(
                new ScreenVertex(0,0,0,0,0,-1), new ScreenVertex(1,0,0,1,0,-1),
                new ScreenVertex(1,1,0,1,1,-1), new ScreenVertex(0,1,0,0,1,-1)));
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
