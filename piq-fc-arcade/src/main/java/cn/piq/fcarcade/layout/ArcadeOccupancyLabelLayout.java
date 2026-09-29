package cn.piq.fcarcade.layout;

public final class ArcadeOccupancyLabelLayout {
    private static final float WATERFRAMES_BIG_TV_TOP = 1.90F;
    private static final float WATERFRAMES_TV_TOP = 1.45F;
    private static final float LABEL_CLEARANCE = 0.38F;

    private ArcadeOccupancyLabelLayout() {
    }

    public static float labelY(ArcadeDisplayStyle displayStyle, int structureHeight) {
        if(cn.piq.fcarcade.home.UserTvLayout.supports(displayStyle))return (float)(cn.piq.fcarcade.home.UserTvLayout.bounds(displayStyle,0).maxY()/16+.2);
        if (displayStyle == ArcadeDisplayStyle.DUAL_CABINET) return (float) DualCabinetGeometry.occupancy(0).y();
        if (displayStyle == ArcadeDisplayStyle.HOME_LCD_TV) return 13.5F/16 + .22F;
        if (displayStyle == ArcadeDisplayStyle.HOME_WIDE_LCD_TV) return 15F/16 + .22F;
        if (displayStyle == ArcadeDisplayStyle.HOME_LARGE_LCD_TV) return 19.5F/16 + .22F;
        if (displayStyle == ArcadeDisplayStyle.HOME_VINTAGE_TV) return 14.3F/16 + .18F;
        float modelTop = switch (displayStyle) {
            case PORTRAIT_CABINET -> 2F;
            case LEGACY_GENERIC -> (float) RocketArcadeGeometry.MODEL_TOP;
            case WATERFRAMES_BIG_TV -> WATERFRAMES_BIG_TV_TOP;
            case WATERFRAMES_TV -> WATERFRAMES_TV_TOP;
            case HOME_RETRO_TV -> (float) (12.7 / 16.0 * cn.piq.fcarcade.home.HomeHardwareScale.TV_SCALE);
            case HOME_LCD_TV -> 13.5F / 16;
            case HOME_WIDE_LCD_TV -> 15F / 16;
            case HOME_LARGE_LCD_TV -> 19.5F / 16;
            case HOME_VINTAGE_TV -> 14.3F / 16;
            case DUAL_CABINET -> (float) DualCabinetGeometry.MODEL_TOP;
            default -> 1.0F;
        };
        modelTop = Math.max(modelTop, structureHeight);
        return modelTop + LABEL_CLEARANCE;
    }

    public record HorizontalCenter(double x, double z) {}

    /** Center of the north-facing 2x2 footprint, rotated about the anchor block. */
    public static HorizontalCenter homeTvCenter(int quarterTurns) {
        return homeTvCenter(quarterTurns, false);
    }

    public static HorizontalCenter homeTvCenter(int quarterTurns, boolean centered) {
        double x = centered ? 0.5 : 1;
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new HorizontalCenter(0, x);
            case 2 -> new HorizontalCenter(1 - x, 0);
            case 3 -> new HorizontalCenter(1, 1 - x);
            default -> new HorizontalCenter(x, 1);
        };
    }

    public static HorizontalCenter homeTvScreenCenter(int quarterTurns, boolean centered) {
        double x = centered ? 0.5 : 1;
        double z = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.HOME_RETRO_TV).frontInset() - 0.001;
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new HorizontalCenter(1 - z, x);
            case 2 -> new HorizontalCenter(1 - x, 1 - z);
            case 3 -> new HorizontalCenter(z, 1 - x);
            default -> new HorizontalCenter(x, z);
        };
    }
}
