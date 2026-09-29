package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import cn.piq.fcarcade.layout.ScreenAspectFit.Aspect;

/** Immutable legacy/compact fits, shared by players and spectators without per-frame allocations. */
public final class DualScreenPresentation {
    private static final ScreenQuad[][] FRAMES = createFrames(false);
    private static final ScreenQuad[][] COMPACT_FRAMES = createFrames(true);
    private DualScreenPresentation() {}
    private static ScreenQuad[][] createFrames(boolean compact) {
        ScreenQuad[][] result=new ScreenQuad[Aspect.values().length][4];
        for (Aspect aspect:Aspect.values()) for (int turn=0;turn<4;turn++)
            result[aspect.ordinal()][turn]=ScreenAspectFit.fit(DualCabinetGeometry.screen(turn,compact),aspect.ratio());
        return result;
    }
    public static ScreenQuad frame(int turns, Aspect aspect) {
        return frame(turns,aspect,false);
    }
    public static ScreenQuad frame(int turns, Aspect aspect, boolean compact) {
        return (compact?COMPACT_FRAMES:FRAMES)[aspect.ordinal()][Math.floorMod(turns,4)];
    }
}
