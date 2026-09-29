package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.home.LargeLcdTvLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.List;

/** Fixed complete 4:3 FC picture inside the new physical 16:9 large LCD, cached for four facings. */
public final class LargeLcdPresentation {
    private static final List<ScreenQuad> FRAMES=java.util.stream.IntStream.range(0,4)
            .mapToObj(t->ScreenAspectFit.fit(LargeLcdTvLayout.screen(t),4D/3)).toList();
    private LargeLcdPresentation() {}
    public static ScreenQuad frame(int turns){return FRAMES.get(Math.floorMod(turns,4));}
}
