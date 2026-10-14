package cn.piq.replaycompat.client;

import cn.piq.replaycompat.api.ScreenFrame;
import cn.piq.replaycompat.api.ScreenVertex;
import java.util.*;

/** Deterministic presentation decisions only: no Minecraft, graphics context or audio device. */
public final class ScreenPresentationCacheTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static int checks;
    public static void main(String[] args) {
        immutablePixelAccess(); pausedAndRepeatedPixels(); sharedImagesAndGeometry(); collisionAndDimensions();
        pendingSeekCloseAndDimension(); saturatedSlots(); frustumVisibility(); uploadSimulation();
        System.out.println("ScreenPresentationCacheTest: " + checks + " assertions passed");
    }
    private static void immutablePixelAccess() {
        int[] caller = {3, 5}; ScreenFrame original = frame("a", 10, 2, 1, caller, 0);
        caller[0] = 9; equal(3, original.pixelAbgr(0));
        int[] exported = original.abgr(); exported[0] = 11; equal(3, original.pixelAbgr(0));
        ScreenFrame changedMetadata = new ScreenFrame("b", "minecraft:the_nether", 20, 2, 1, new int[]{3, 5}, quad(20));
        check(original.samePixels(changedMetadata)); equal(original.pixelHash(), changedMetadata.pixelHash());
        check(!original.samePixels(null)); check(!original.samePixels(frame("a", 0, 2, 1, new int[]{3, 6}, 0)));
        try { original.pixelAbgr(-1); throw new AssertionError("negative pixel index accepted"); }
        catch (IndexOutOfBoundsException expected) { checks++; }
        try { original.pixelAbgr(2); throw new AssertionError("large pixel index accepted"); }
        catch (IndexOutOfBoundsException expected) { checks++; }
        check(original.at(500).samePixels(original)); equal(10L, original.timestampMillis());
    }
    private static void pausedAndRepeatedPixels() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); ScreenFrame first = frame("a", 10, 9, 0);
        var initial = select(cache, List.of(first)); var image = initial.draws().getFirst().image();
        var metrics = cache.metrics();
        for (int tick = 0; tick < 1_000; tick++) {
            var next = select(cache, List.of(first));
            same(image, next.draws().getFirst().image()); same(first, next.draws().getFirst().frame());
        }
        equal(metrics, cache.metrics()); // Paused renders perform zero pixel hashing/comparison/upload assignments.
        for (int tick = 0; tick < 100; tick++) {
            ScreenFrame retimed = frame("a", 20 + tick, 9, tick + 1);
            var next = select(cache, List.of(retimed));
            same(image, next.draws().getFirst().image()); same(retimed, next.draws().getFirst().frame());
        }
        equal(1L, cache.metrics().assignments()); equal(101L, cache.metrics().hashes());
        check(cache.metrics().comparisons() >= 100);
    }
    private static void sharedImagesAndGeometry() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); List<ScreenFrame> frames = new ArrayList<>();
        for (int i = 0; i < 16; i++) frames.add(frame("screen" + i, i, 17, i * 2));
        var plan = select(cache, frames); equal(16, plan.draws().size()); equal(1, plan.retainedImages().size());
        var image = plan.retainedImages().getFirst();
        for (int i = 0; i < 16; i++) {
            same(image, plan.draws().get(i).image()); same(frames.get(i), plan.draws().get(i).frame());
            equal((double) (i * 2), plan.draws().get(i).frame().vertices().getFirst().x());
        }
        equal(1L, cache.metrics().assignments());
        // Closing one owner of shared content cannot remove another owner's texture.
        var one = cache.select(List.of(frames.get(4)), Set.of("screen4"), DIMENSION, bounds -> true);
        same(image, one.draws().getFirst().image()); equal(1, one.retainedImages().size());
    }
    private static void collisionAndDimensions() {
        ScreenFrame first = frame("a", 0, 2, 1, new int[]{0, 31}, 0);
        ScreenFrame collision = frame("b", 0, 2, 1, new int[]{1, 0}, 0);
        equal(first.pixelHash(), collision.pixelHash()); check(!first.samePixels(collision));
        ScreenPresentationCache cache = new ScreenPresentationCache(); var plan = select(cache, List.of(first, collision));
        equal(2, plan.retainedImages().size()); check(plan.draws().get(0).image().slot() != plan.draws().get(1).image().slot());
        ScreenFrame tall = frame("a", 1, 1, 2, new int[]{0, 31}, 0);
        equal(tall.pixelHash(), first.pixelHash()); check(!tall.samePixels(first));
        var resized = select(cache, List.of(tall, collision));
        check(resized.draws().getFirst().image().version() != plan.draws().getFirst().image().version());
        equal(1, resized.draws().getFirst().image().pixels().width()); equal(2, resized.draws().getFirst().image().pixels().height());
        for (var draw : resized.draws()) check(draw.frame().samePixels(draw.image().pixels()));
    }
    private static void pendingSeekCloseAndDimension() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); ScreenFrame old = frame("a", 100, 1, 0);
        var first = select(cache, List.of(old)); var image = first.draws().getFirst().image();
        var pending = cache.select(List.of(), Set.of("a"), DIMENSION, b -> true);
        equal(0, pending.draws().size()); equal(1, pending.retainedImages().size()); same(image, pending.retainedImages().getFirst());
        ScreenFrame other = frame("b", 200, 2, 10);
        var partial = cache.select(List.of(other), Set.of("a", "b"), DIMENSION, b -> true);
        equal(1, partial.draws().size()); same(other, partial.draws().getFirst().frame()); equal(2, partial.retainedImages().size());
        ScreenFrame seek = frame("a", 5, 7, 99);
        var selected = cache.select(List.of(seek, other), Set.of("a", "b"), DIMENSION, b -> true);
        same(seek, selected.draws().getFirst().frame()); equal(7, selected.draws().getFirst().image().pixels().pixelAbgr(0));
        check(selected.draws().getFirst().image().version() != image.version());
        var close = cache.select(List.of(ScreenFrame.closed("a", DIMENSION, 6), other), Set.of("a", "b"), DIMENSION, b -> true);
        equal(1, close.draws().size()); same(other, close.draws().getFirst().frame()); equal(1, close.retainedImages().size());
        var wrongDimension = cache.select(List.of(other), Set.of("b"), "minecraft:the_nether", b -> true);
        equal(0, wrongDimension.draws().size()); equal(0, wrongDimension.retainedImages().size());
        var empty = cache.select(List.of(), Set.of(), DIMENSION, b -> true); equal(0, empty.retainedImages().size());
        cache.clear(); var again = select(cache, List.of(old));
        check(again.draws().getFirst().image().version() > image.version()); // Old GPU version cannot alias after clearing.
        cache.select(List.of(), Set.of(), DIMENSION, b -> true);
        var reopen = select(cache, List.of(old)); check(reopen.draws().getFirst().image().version() > again.draws().getFirst().image().version());
    }
    private static void saturatedSlots() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); List<ScreenFrame> frames = new ArrayList<>();
        for (int i = 0; i < 16; i++) frames.add(frame("s" + i, 0, i, i));
        var all = select(cache, frames); equal(16, all.retainedImages().size());
        List<ScreenFrame> changed = new ArrayList<>(frames); changed.set(0, frame("s0", 1, 100, 0));
        var change = select(cache, changed); equal(16, change.retainedImages().size());
        for (int i = 1; i < 16; i++) same(all.draws().get(i).image(), change.draws().get(i).image());
        for (var draw : change.draws()) check(draw.frame().samePixels(draw.image().pixels()));
        for (int tick = 2; tick < 100; tick++) {
            var next = new ArrayList<ScreenFrame>();
            for (int i = 0; i < 16; i++) next.add(frame("s" + i, tick, tick * 16 + i, i));
            var plan = select(cache, next); equal(16, plan.draws().size()); equal(16, plan.retainedImages().size());
            for (var draw : plan.draws()) {
                check(draw.image().slot() >= 0 && draw.image().slot() < 16);
                check(draw.frame().samePixels(draw.image().pixels()));
            }
        }
        // Ready content can displace old pending allocations rather than starving indefinitely.
        var active = new HashSet<String>(); for (int i = 0; i < 16; i++) active.add("s" + i); active.add("new");
        var pendingFull = cache.select(List.of(frame("new", 200, 777, 0)), active, DIMENSION, b -> true);
        equal(1, pendingFull.draws().size()); equal("new", pendingFull.draws().getFirst().frame().source());
        check(pendingFull.retainedImages().size() <= 16);
        var overflow = new ArrayList<>(frames); overflow.add(frame("seventeenth", 0, 99, 0));
        equal(16, select(cache, overflow).draws().size());
    }
    private static void frustumVisibility() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); ScreenFrame near = frame("near", 0, 1, 0);
        ScreenFrame far = frame("far", 0, 2, 20_000_000);
        var bounds = ScreenPresentationCache.bounds(far);
        check(bounds.minX() < 20_000_000 && bounds.maxX() > 20_000_001);
        check(bounds.minZ() < 0 && bounds.maxZ() > 0); // Planar geometry remains nondegenerate.
        var both = select(cache, List.of(near, far)); equal(2, both.draws().size()); // No watching-distance cutoff.
        var metrics = cache.metrics();
        var hidden = cache.select(List.of(near, far), Set.of("near", "far"), DIMENSION, b -> false);
        equal(0, hidden.draws().size()); equal(2, hidden.retainedImages().size()); equal(metrics, cache.metrics());
        var otherCamera = cache.select(List.of(near, far), Set.of("near", "far"), DIMENSION, b -> b.minX() > 1000);
        equal(1, otherCamera.draws().size()); same(far, otherCamera.draws().getFirst().frame());
        var restored = select(cache, List.of(near, far)); same(both.draws().getFirst().image(), restored.draws().getFirst().image());
    }
    private static void uploadSimulation() {
        ScreenPresentationCache cache = new ScreenPresentationCache(); Uploads uploads = new Uploads();
        var first = frame("a", 0, 99, 0); var shared = frame("b", 0, 99, 20);
        uploads.present(select(cache, List.of(first, shared)), false); equal(1, uploads.count);
        for (int time = 1; time <= 100; time++) uploads.present(select(cache,
                List.of(frame("a", time, 99, time), frame("b", time, 99, time + 20))), false);
        equal(1, uploads.count);
        var changed = select(cache, List.of(frame("a", 101, 100, 0), shared));
        uploads.present(changed, true); equal(1, uploads.count); // Failed upload cannot publish a physical version.
        uploads.present(changed, false); equal(2, uploads.count);
        uploads.present(cache.select(List.of(), Set.of("a", "b"), DIMENSION, b -> true), false);
        equal(0, uploads.drawn); equal(2, uploads.count);
        uploads.present(cache.select(List.of(), Set.of(), DIMENSION, b -> true), false);
        equal(0L, Arrays.stream(uploads.versions).filter(v -> v != 0).count());
    }
    private static final class Uploads {
        final long[] versions = new long[16]; int count, drawn;
        void present(ScreenPresentationCache.Plan plan, boolean fail) {
            boolean[] retained = new boolean[16]; for (var image : plan.retainedImages()) retained[image.slot()] = true;
            for (int slot = 0; slot < 16; slot++) if (!retained[slot]) versions[slot] = 0;
            drawn = 0;
            for (var draw : plan.draws()) {
                var image = draw.image();
                if (versions[image.slot()] != image.version()) {
                    if (fail) continue;
                    ScreenFrame pixels = image.pixels();
                    for (int p = 0; p < pixels.width() * pixels.height(); p++) equal(pixels.pixelAbgr(p), draw.frame().pixelAbgr(p));
                    versions[image.slot()] = image.version(); count++;
                }
                drawn++;
            }
        }
    }
    private static ScreenPresentationCache.Plan select(ScreenPresentationCache cache, List<ScreenFrame> frames) {
        Set<String> sources = new HashSet<>(); for (var frame : frames) sources.add(frame.source());
        return cache.select(frames, sources, DIMENSION, b -> true);
    }
    private static ScreenFrame frame(String source, long time, int value, double x) {
        return frame(source, time, 1, 1, new int[]{value}, x);
    }
    private static ScreenFrame frame(String source, long time, int width, int height, int[] pixels, double x) {
        return new ScreenFrame(source, DIMENSION, time, width, height, pixels, quad(x));
    }
    private static List<ScreenVertex> quad(double x) {
        return List.of(new ScreenVertex(x, 0, 0, 0, 0, -1), new ScreenVertex(x + 1, 0, 0, 1, 0, -1),
                new ScreenVertex(x + 1, 1, 0, 1, 1, -1), new ScreenVertex(x, 1, 0, 0, 1, -1));
    }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void same(Object expected, Object actual) { check(expected == actual); }
    private static void equal(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", actual " + actual); checks++; }
}
