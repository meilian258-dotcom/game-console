package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import java.util.Arrays;

/** Reports strict old->old controls before judging a new bridge's restore audio. */
public final class LibretroAudioRestoreDiagnostic {
    public static void main(String[] args) {
        for (boolean generic : new boolean[]{false, true}) {
            for (boolean restoreSource : new boolean[]{false, true}) compare(generic, restoreSource);
        }
    }
    private static void compare(boolean generic, boolean restoreSource) {
        byte[] rom = GenericLibretroCompatibilitySmoke.diagnosticRom();
        try (NesCore source = new LibretroNesCore(false);
             NesCore target = generic ? new GenericLibretroNesCore(false) : new LibretroNesCore(false)) {
            source.loadRom(rom); target.loadRom(rom);
            for (int i = 0; i < 122; i++) {
                inputs(source, i); inputs(target, i); source.runFrame(); target.runFrame();
                if (!Arrays.equals(audio(source), audio(target))) throw new AssertionError("Fresh audio differs at " + i);
            }
            byte[] state = source.saveTransientState();
            for (int i = 0; i < 9; i++) { inputs(target, i + 210); target.runFrame(); }
            target.loadTransientState(state);
            if (restoreSource) source.loadTransientState(state);
            int frameDifferences = 0, countDifferences = 0, sampleDifferences = 0, firstFrame = -1, lastFrame = -1;
            float maxDifference = 0;
            byte[] sourceVideo = new byte[NesCore.RGBA_BYTES], targetVideo = new byte[NesCore.RGBA_BYTES];
            byte[] sourceRam = new byte[NesCore.CPU_RAM_BYTES], targetRam = new byte[NesCore.CPU_RAM_BYTES];
            for (int i = 0; i < 61; i++) {
                if (i > 0) { inputs(source, i + 299); inputs(target, i + 299); }
                source.runFrame(); target.runFrame();
                source.copyFrameRgba(sourceVideo); target.copyFrameRgba(targetVideo);
                source.copyCpuRam(sourceRam); target.copyCpuRam(targetRam);
                if (!Arrays.equals(sourceVideo, targetVideo) || !Arrays.equals(sourceRam, targetRam))
                    throw new AssertionError("Video/RAM differs after restore at " + i);
                float[] a = audio(source), b = audio(target);
                if (a.length != b.length) countDifferences++;
                boolean different = a.length != b.length;
                for (int n = 0; n < Math.min(a.length, b.length); n++) {
                    if (Float.floatToRawIntBits(a[n]) != Float.floatToRawIntBits(b[n])) {
                        if (!different && firstFrame < 0) firstFrame = i;
                        different = true; sampleDifferences++; maxDifference = Math.max(maxDifference, Math.abs(a[n] - b[n]));
                    }
                }
                if (different) { frameDifferences++; lastFrame = i; }
            }
            System.out.println("target=" + (generic ? "generic" : "legacy") + " restoreSource=" + restoreSource
                    + " comparedFrames=61 videoRamEqual=true audioDifferentFrames=" + frameDifferences
                    + " audioCountDifferentFrames=" + countDifferences
                    + " audioDifferentSamples=" + sampleDifferences + " firstDifferentFrame=" + firstFrame
                    + " lastDifferentFrame=" + lastFrame + " maxAbsDifference=" + maxDifference);
            if (restoreSource && frameDifferences != 0) throw new AssertionError("Both-restored audio differs");
        }
    }
    private static void inputs(NesCore core, int tick) {
        core.setControllerState(0, (tick * 37 + 11) & 255);
        core.setControllerState(1, (tick * 19 + 73) & 255);
    }
    private static float[] audio(NesCore core) {
        float[] samples = new float[4096];
        return Arrays.copyOf(samples, core.copyAudioSamples(samples));
    }
}
