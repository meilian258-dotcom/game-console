package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.home.HomeHardwareScale;
import cn.piq.fcarcade.home.VintageTvLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.util.Objects;

/** Actual NES image surface, including fitted black bars (not the whole physical glass).
 * Translation stays separate: the renderer applies it to PoseStack before float vertices,
 * and ray mapping removes it from the eye exactly once. All coordinates are anchor-local. */
public final class ScreenSurfaceGeometry {
    private static final Point ZERO = new Point(0, 0, 0);
    private ScreenSurfaceGeometry() {}

    public record Surface(ScreenQuad image, Point translation) {
        public Surface { Objects.requireNonNull(image); Objects.requireNonNull(translation); }
    }

    public static Surface frame(ArcadeDisplayStyle style, int turns, int width, int height,
                                boolean centered, ScreenAspectFit.Aspect dualAspect) {
        return frame(style,turns,width,height,centered,dualAspect,false);
    }
    public static Surface frame(ArcadeDisplayStyle style, int turns, int width, int height,
                                boolean centered, ScreenAspectFit.Aspect dualAspect, boolean compactDual) {
        Objects.requireNonNull(style);
        int turn = Math.floorMod(turns, 4);
        ScreenQuad image = switch (style) {
            case PORTRAIT_CABINET -> ScreenAspectFit.fit(PortraitCabinetGeometry.screen(turn),4D/3);
            case LEGACY_GENERIC -> RocketArcadeGeometry.screen(turn);
            case DUAL_CABINET -> DualScreenPresentation.frame(turn, Objects.requireNonNull(dualAspect),compactDual);
            case HOME_WIDE_LCD_TV -> WideLcdPresentation.frame(turn);
            case HOME_LARGE_LCD_TV -> LargeLcdPresentation.frame(turn);
            case HOME_VINTAGE_TV -> VintageTvLayout.screen(turn);
            case HOME_GRAY_CRT,HOME_RED_CRT,HOME_PANEL_2,HOME_PANEL_2_WALL,HOME_PANEL_3,HOME_PANEL_3_WALL -> cn.piq.fcarcade.home.UserTvLayout.screen(style,turn);
            default -> flat(style, turn, width, height);
        };
        return new Surface(image, translation(style, turn, centered));
    }

    public static Point translation(ArcadeDisplayStyle style, int turns, boolean centered) {
        if (style != ArcadeDisplayStyle.HOME_RETRO_TV || !centered) return ZERO;
        return switch (Math.floorMod(turns, 4)) {
            case 0 -> new Point(-.5, 0, 0);
            case 1 -> new Point(0, 0, -.5);
            case 2 -> new Point(.5, 0, 0);
            default -> new Point(0, 0, .5);
        };
    }

    private static ScreenQuad flat(ArcadeDisplayStyle style, int turn, int width, int height) {
        ArcadeScreenBounds b = ArcadeScreenBounds.resolve(width, height, style);
        // Keep the original float operations/order, including asymmetric multi-block bounds.
        float offset = style == ArcadeDisplayStyle.HOME_RETRO_TV
                ? .00025F * (float) HomeHardwareScale.TV_SCALE : .002F;
        float near = b.frontInset() - offset;
        float far = 1F - b.frontInset() + offset;
        return switch (turn) {
            case 0 -> new ScreenQuad(p(b.negativeMin(), b.bottom(), near), p(b.max(), b.bottom(), near),
                    p(b.max(), b.top(), near), p(b.negativeMin(), b.top(), near), p(0, 0, -1));
            case 1 -> new ScreenQuad(p(far, b.bottom(), b.negativeMin()), p(far, b.bottom(), b.max()),
                    p(far, b.top(), b.max()), p(far, b.top(), b.negativeMin()), p(1, 0, 0));
            case 2 -> new ScreenQuad(p(b.positiveMax(), b.bottom(), far), p(b.min(), b.bottom(), far),
                    p(b.min(), b.top(), far), p(b.positiveMax(), b.top(), far), p(0, 0, 1));
            default -> new ScreenQuad(p(near, b.bottom(), b.positiveMax()), p(near, b.bottom(), b.min()),
                    p(near, b.top(), b.min()), p(near, b.top(), b.positiveMax()), p(-1, 0, 0));
        };
    }

    private static Point p(float x, float y, float z) { return new Point(x, y, z); }
}
