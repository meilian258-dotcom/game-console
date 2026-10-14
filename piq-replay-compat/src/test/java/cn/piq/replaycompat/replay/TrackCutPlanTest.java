package cn.piq.replaycompat.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** Pure Java main-based tests; no Minecraft, ReplayStudio, or test framework. */
public final class TrackCutPlanTest {
    private static int checks;

    public static void main(String[] args) {
        identityIncludesLastMillisecond();
        pauseRemovesHalfOpenRange();
        multipleCutsAccumulateOffsets();
        initialAndTrailingCuts();
        splitResetsOutputTime();
        stopAndRestartSplitsInsideCut();
        emptyOutputsKeepTheirIndexes();
        exactDurationSplitKeepsFinalInstant();
        markersPastPacketHorizonDoNotCreateOutputs();
        boundarySeedsPreserveFrameAndBlankState();
        badMarkersFailExplicitly();
        inputAndOutputCollectionsAreImmutable();
        System.out.println("TrackCutPlanTest: " + checks + " checks passed");
    }

    private static void identityIncludesLastMillisecond() {
        TrackCutPlan plan = plan(100);
        equal(List.of(segment(0, 101, 0)), plan.output(0).segments());
        mapped(plan, 0, 0, 0);
        mapped(plan, 0, 100, 100);
        absent(plan, 0, -1);
        absent(plan, 0, 101);
        TrackCutPlan maximum = plan(Integer.MAX_VALUE);
        equal(2147483648L, maximum.output(0).segments().get(0).sourceTo());
        mapped(maximum, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        mapped(plan(0), 0, 0, 0);
    }

    private static void pauseRemovesHalfOpenRange() {
        TrackCutPlan plan = plan(100, start(20), end(50));
        equal(List.of(segment(0, 20, 0), segment(50, 101, 20)), plan.output(0).segments());
        mapped(plan, 0, 19, 19);
        absent(plan, 0, 20);
        absent(plan, 0, 49);
        mapped(plan, 0, 50, 20);
        mapped(plan, 0, 100, 70);
    }

    private static void multipleCutsAccumulateOffsets() {
        TrackCutPlan plan = plan(100, start(10), end(20), start(40), end(70));
        equal(List.of(segment(0, 10, 0), segment(20, 40, 10), segment(70, 101, 30)),
                plan.output(0).segments());
        mapped(plan, 0, 100, 60);
    }

    private static void initialAndTrailingCuts() {
        TrackCutPlan plan = plan(100, start(0), end(30), start(80));
        equal(List.of(segment(30, 80, 0)), plan.output(0).segments());
        mapped(plan, 0, 30, 0);
        absent(plan, 0, 80);
        absent(plan, 0, 100);
        equal(List.of(), plan(100, start(0)).output(0).segments());
        equal(List.of(segment(0, 100, 0)), plan(100, start(100)).output(0).segments());
    }

    private static void splitResetsOutputTime() {
        TrackCutPlan plan = plan(100, start(10), end(20), split(50), split(80));
        equal(3, plan.outputs().size());
        equal(List.of(segment(0, 10, 0), segment(20, 50, 10)), plan.output(0).segments());
        equal(List.of(segment(50, 80, 0)), plan.output(1).segments());
        equal(List.of(segment(80, 101, 0)), plan.output(2).segments());
        absent(plan, 0, 50);
        mapped(plan, 1, 50, 0);
        mapped(plan, 2, 100, 20);
    }

    private static void stopAndRestartSplitsInsideCut() {
        // GuiRecordingControls stops at t, splits at t+1, resumes with END_CUT.
        TrackCutPlan plan = plan(100, start(20), split(21), end(60));
        equal(List.of(segment(0, 20, 0)), plan.output(0).segments());
        equal(List.of(segment(60, 101, 0)), plan.output(1).segments());
        mapped(plan, 1, 60, 0);
        mapped(plan, 1, 100, 40);
        TrackCutPlan stoppedWhilePaused = plan(100, start(10), split(31), end(60));
        equal(List.of(segment(0, 10, 0)), stoppedWhilePaused.output(0).segments());
        equal(List.of(segment(60, 101, 0)), stoppedWhilePaused.output(1).segments());
    }

    private static void emptyOutputsKeepTheirIndexes() {
        TrackCutPlan plan = plan(100, start(0), split(10), split(20), end(30));
        equal(3, plan.outputs().size());
        equal(0, plan.output(0).index());
        equal(1, plan.output(1).index());
        equal(List.of(), plan.output(0).segments());
        equal(List.of(), plan.output(1).segments());
        equal(List.of(segment(30, 101, 0)), plan.output(2).segments());
        absent(plan, 0, 30);
        mapped(plan, 2, 30, 0);
        // These planned indexes must not be compressed to match predicted suffixes.
        equal(List.of(), plan(100, split(0)).output(0).segments());
    }

    private static void exactDurationSplitKeepsFinalInstant() {
        TrackCutPlan plan = plan(100, split(100));
        equal(List.of(segment(0, 100, 0)), plan.output(0).segments());
        equal(List.of(segment(100, 101, 0)), plan.output(1).segments());
        mapped(plan, 1, 100, 0);
    }

    private static void markersPastPacketHorizonDoNotCreateOutputs() {
        TrackCutPlan plan = plan(100, start(100), split(101), end(120));
        equal(1, plan.outputs().size());
        equal(List.of(segment(0, 100, 0)), plan.output(0).segments());
        equal(List.of(segment(0, 101, 0)), plan(100, split(150)).output(0).segments());
    }

    private static void boundarySeedsPreserveFrameAndBlankState() {
        TrackCutPlan plan = plan(100, start(20), end(50), split(80));
        TrackCutPlan.Segment resumed = plan.output(0).segments().get(1);
        equal(50L, resumed.sourceFrom());
        equal(20L, resumed.outputFrom());
        // Cut frames are not emitted, but their latest state is seeded at resume.
        List<State> frames = List.of(new State(10, "A"), new State(25, "B"), new State(50, "C"));
        equal("B", stateBefore(frames, resumed.sourceFrom()));
        mapped(plan, 0, 50, 20); // C follows the seed at the same output timestamp.
        TrackCutPlan.Segment split = plan.output(1).segments().get(0);
        equal("C", stateBefore(frames, split.sourceFrom()));
        equal(0L, split.outputFrom());
        // A removed/blank screen is real state, not a reason to resurrect image B.
        List<State> cleared = List.of(new State(10, "A"), new State(25, "B"), new State(49, "BLANK"));
        equal("BLANK", stateBefore(cleared, resumed.sourceFrom()));
        equal("BLANK", stateBefore(List.of(), resumed.sourceFrom()));
    }

    private static void badMarkersFailExplicitly() {
        rejects(() -> plan(-1));
        rejects(() -> plan(100, start(-1)));
        rejects(() -> plan(100, end(20)));
        rejects(() -> plan(100, start(10), start(20)));
        rejects(() -> plan(100, start(10), end(20), end(30)));
        rejects(() -> plan(100, start(10), end(10)));
        rejects(() -> plan(100, split(10), start(10)));
        rejects(() -> plan(100, split(10), split(10)));
        rejects(() -> plan(100, new TrackCutPlan.Marker(10, "_RM_UNKNOWN")));
        // User markers have no effect, including a marker sharing a control time.
        equal(List.of(segment(0, 10, 0), segment(20, 101, 10)),
                plan(100, end(20), new TrackCutPlan.Marker(10, null), start(10),
                        new TrackCutPlan.Marker(10, "chapter")).output(0).segments());
    }

    private static void inputAndOutputCollectionsAreImmutable() {
        List<TrackCutPlan.Marker> markers = new ArrayList<>(List.of(split(50)));
        TrackCutPlan plan = TrackCutPlan.create(100, markers);
        markers.clear();
        equal(2, plan.outputs().size());
        unsupported(() -> plan.outputs().clear());
        unsupported(() -> plan.output(0).segments().clear());
    }

    private record State(long time, String value) { }

    private static String stateBefore(List<State> frames, long sourceTime) {
        String state = "BLANK";
        for (State frame : frames) {
            if (frame.time() >= sourceTime) {
                break;
            }
            state = frame.value();
        }
        return state;
    }

    private static TrackCutPlan plan(int duration, TrackCutPlan.Marker... markers) {
        return TrackCutPlan.create(duration, List.of(markers));
    }

    private static TrackCutPlan.Marker start(int time) { return new TrackCutPlan.Marker(time, TrackCutPlan.START_CUT); }
    private static TrackCutPlan.Marker end(int time) { return new TrackCutPlan.Marker(time, TrackCutPlan.END_CUT); }
    private static TrackCutPlan.Marker split(int time) { return new TrackCutPlan.Marker(time, TrackCutPlan.SPLIT); }
    private static TrackCutPlan.Segment segment(long from, long to, long output) {
        return new TrackCutPlan.Segment(from, to, output);
    }

    private static void mapped(TrackCutPlan plan, int output, long source, long expected) {
        equal(OptionalLong.of(expected), plan.map(output, source));
    }

    private static void absent(TrackCutPlan plan, int output, long source) {
        equal(OptionalLong.empty(), plan.map(output, source));
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    private static void unsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
