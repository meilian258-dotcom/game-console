package cn.piq.fcarcade.layout;

public enum ArcadeDisplayStyle {
    CLASSIC,
    DELUXE,
    LEGACY_GENERIC,
    WATERFRAMES_BIG_TV,
    WATERFRAMES_TV,
    WATERFRAMES_TV_BOX,
    WATERFRAMES_PANEL,
    HOME_RETRO_TV,
    HOME_LCD_TV,
    DUAL_CABINET,
    HOME_WIDE_LCD_TV,
    HOME_LARGE_LCD_TV,
    HOME_VINTAGE_TV,
    HOME_GRAY_CRT,
    HOME_RED_CRT,
    HOME_PANEL_2,
    HOME_PANEL_2_WALL,
    HOME_PANEL_3,
    HOME_PANEL_3_WALL,
    PORTRAIT_CABINET;

    public boolean usesWaterFramesAssets() {
        return switch (this) {
            case WATERFRAMES_BIG_TV,
                 WATERFRAMES_TV,
                 WATERFRAMES_TV_BOX,
                 WATERFRAMES_PANEL -> true;
            default -> false;
        };
    }
}
