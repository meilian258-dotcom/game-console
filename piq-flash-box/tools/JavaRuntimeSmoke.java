import cn.piq.flashbox.runtime.FlashRuntime;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class JavaRuntimeSmoke {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static FlashRuntime.Frame next(FlashRuntime runtime, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            FlashRuntime.Frame frame = runtime.poll();
            if (frame != null) {
                require(frame.abgr().length == 640 * 480, "Unexpected pixel count");
                return frame;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No decoded frame: " + runtime.status());
    }
    private static String sha(FlashRuntime.Frame frame) throws Exception {
        ByteBuffer pixels = ByteBuffer.allocate(frame.abgr().length * 4);
        for (int value : frame.abgr()) pixels.putInt(value);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels.array()));
    }
    private static List<ProcessHandle> helperChildren() {
        return ProcessHandle.current().descendants().filter(p -> p.info().command().orElse("").toLowerCase(Locale.ROOT).endsWith("flashbox.helper.exe")).toList();
    }
    private static void awaitExit(Collection<ProcessHandle> processes) throws Exception {
        long deadline = System.nanoTime() + 9_000_000_000L;
        while (System.nanoTime() < deadline && processes.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(50);
        require(processes.stream().noneMatch(ProcessHandle::isAlive), "An owned helper remains alive after close");
    }
    public static void main(String[] args) throws Exception {
        Path gameDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path swf = Path.of(args[1]).toRealPath();
        Path reportPath = Path.of(args[2]);
        String origin = FlashRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString();
        require(args.length==4 && origin.endsWith("game_console_flash_box-"+args[3]+".jar"), "Production class was not loaded from selected frozen JAR");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("at", Instant.now().toString());
        report.put("productionOrigin", origin);
        report.put("javaVersion", System.getProperty("java.version"));
        List<Map<String, Object>> cases = new ArrayList<>();
        report.put("cases", cases);
        Throwable failure = null;
        try {
            long started = System.nanoTime();
            FlashRuntime runtime = new FlashRuntime(gameDir, swf);
            List<ProcessHandle> owned = new ArrayList<>();
            try {
                long deadline = started + 40_000_000_000L;
                while (!runtime.ready() && System.nanoTime() < deadline) Thread.sleep(25);
                require(runtime.ready(), "Runtime not ready within 40s: " + runtime.status());
                owned.addAll(helperChildren());
                require(!owned.isEmpty(), "Could not identify owned helper");
                FlashRuntime.Frame first = next(runtime, 4000);
                long firstMs = (System.nanoTime() - started) / 1_000_000;
                require(firstMs < 40_000, "First frame exceeded 40s");
                long distinct = Arrays.stream(first.abgr()).distinct().limit(64).count();
                require(distinct > 8, "First frame did not contain meaningful color variation");
                long prior = first.sequence();
                for (int i = 0; i < 8; i++) {
                    var current = next(runtime, 2000);
                    require(current.sequence() > prior, "Decoded frame sequence did not advance");
                    prior = current.sequence();
                }
                runtime.keys(2, 1); Thread.sleep(100); runtime.keys(0, 0);
                runtime.mouse(320, 430, true); runtime.mouse(320, 430, false);
                Thread.sleep(300);
                runtime.pause(); Thread.sleep(600);
                runtime.poll();
                var paused1 = next(runtime, 1500);
                Thread.sleep(1200); runtime.poll();
                var paused2 = next(runtime, 1500);
                require(sha(paused1).equals(sha(paused2)), "Paused Java frames differed");
                runtime.resume();
                var resumed = next(runtime, 2000);
                require(resumed.sequence() > paused2.sequence(), "No frame after resume");
                cases.add(Map.of("case", "frozen-java-to-helper", "ok", true,
                        "readyFirstFrameMs", firstMs, "pixels", first.abgr().length,
                        "firstSequence", first.sequence(), "resumeSequence", resumed.sequence(),
                        "pauseFrameSha256", sha(paused1), "helperPids", owned.stream().map(ProcessHandle::pid).toList()));
            } finally {
                runtime.close();
                awaitExit(owned);
            }
            cases.add(Map.of("case", "close-no-owned-helper", "ok", true));
            Path asset = gameDir.resolve("piq-flash-box/runtime/web/player.js");
            Path backup = asset.resolveSibling("player.js.smoke-backup");
            require(asset.toRealPath().startsWith(gameDir.toRealPath()), "Negative test escaped isolated directory");
            Files.move(asset, backup);
            boolean rejected = false;
            try {
                try (FlashRuntime unexpected = new FlashRuntime(gameDir, swf)) {
                    throw new AssertionError("Missing frozen file was accepted");
                } catch (java.io.IOException expected) {
                    rejected = expected.getMessage().contains("player.js");
                }
            } finally { Files.move(backup, asset); }
            require(rejected, "Missing-file failure did not identify the missing file");
            require(helperChildren().isEmpty(), "Missing-file test launched a helper");
            cases.add(Map.of("case", "missing-frozen-file-rejected-before-launch", "ok", true));
            AtomicInteger cancellationChecks = new AtomicInteger();
            boolean cancelled = false;
            try (FlashRuntime unexpected = new FlashRuntime(gameDir, swf, () -> { cancellationChecks.incrementAndGet(); return true; })) {
                throw new AssertionError("Cancelled construction succeeded");
            } catch (java.io.IOException expected) { cancelled = expected.getMessage().contains("取消"); }
            require(cancelled && cancellationChecks.get() > 0, "Cancellation was not applied before launch");
            require(helperChildren().isEmpty(), "Cancelled construction launched a helper");
            cases.add(Map.of("case", "cancelled-construction-does-not-launch", "ok", true, "cancellationChecks", cancellationChecks.get()));
            report.put("ok", true);
        } catch (Throwable error) {
            failure = error; report.put("ok", false); report.put("failure", error.toString());
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report);
        Files.writeString(reportPath, json + "\n");
        System.out.println(json);
        if (failure != null) throw new AssertionError("Java/helper smoke failed", failure);
    }
}
