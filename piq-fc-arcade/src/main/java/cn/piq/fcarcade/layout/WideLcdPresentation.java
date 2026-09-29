package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.home.WideLcdTvLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.List;

/** Physical LCD is 16:9; standard FC video is 4:3, centered with real black glass at its sides. */
public final class WideLcdPresentation {
    private static final List<ScreenQuad> FRAMES = java.util.stream.IntStream.range(0,4)
            .mapToObj(turn -> ScreenAspectFit.fit(WideLcdTvLayout.screen(turn), 4D/3)).toList();
    private WideLcdPresentation() {}
    public static ScreenQuad frame(int turns) { return FRAMES.get(Math.floorMod(turns,4)); }
}
