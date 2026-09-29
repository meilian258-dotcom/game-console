package cn.piq.fcarcade.layout;

public record ArcadeScreenBounds(
        float min,
        float max,
        float negativeMin,
        float positiveMax,
        float bottom,
        float top,
        float frontInset
) {
    private static final float NES_ASPECT = 4.0F / 3.0F;

    public static ArcadeScreenBounds resolve(
            int width,
            int height,
            ArcadeDisplayStyle style
    ) {
        if (cn.piq.fcarcade.home.UserTvLayout.supports(style)) {
            var q=cn.piq.fcarcade.home.UserTvLayout.screen(style,0);
            float left=(float)q.lowerMinX().x(),right=(float)q.lowerMaxX().x();
            return new ArcadeScreenBounds(1-right,right,left,1-left,(float)q.lowerMinX().y(),(float)q.upperMinX().y(),(float)q.center().z());
        }
        if (style == ArcadeDisplayStyle.HOME_RETRO_TV) return homeTvScreenBounds();
        if (style == ArcadeDisplayStyle.HOME_LCD_TV) return new ArcadeScreenBounds(
                1F/16,15F/16,1F/16,15F/16,1.5F/16,12F/16,6F/16);
        if (style == ArcadeDisplayStyle.HOME_WIDE_LCD_TV) return new ArcadeScreenBounds(
                1F/16,23F/16,1F/16,23F/16,1.5F/16,13.875F/16,6F/16);
        if (style == ArcadeDisplayStyle.HOME_LARGE_LCD_TV) return new ArcadeScreenBounds(
                -7F/16,23F/16,-7F/16,23F/16,1.5F/16,18.375F/16,6F/16);
        if (style == ArcadeDisplayStyle.HOME_VINTAGE_TV) return new ArcadeScreenBounds(
                4.35F/16,14.55F/16,4.35F/16,14.55F/16,2F/16,9.65F/16,3.35F/16);
        if (style == ArcadeDisplayStyle.DUAL_CABINET) return dualScreenBounds();
        if (style == ArcadeDisplayStyle.WATERFRAMES_PANEL) {
            return fitted(width, height, 0.03F, 0.96875F);
        }
        if (width > 1 || height > 1) {
            float contentHeight = (width - 0.20F) / NES_ASPECT;
            float bottom = (height - contentHeight) * 0.5F;
            return new ArcadeScreenBounds(
                    0.10F,
                    0.90F,
                    1.10F - width,
                    width - 0.10F,
                    bottom,
                    height - bottom,
                    0.0F);
        }
        return switch (style) {
            case WATERFRAMES_BIG_TV -> new ArcadeScreenBounds(
                    -0.60F,
                    1.60F,
                    -0.60F,
                    1.60F,
                    0.25F,
                    1.90F,
                    0.125F);
            case WATERFRAMES_TV -> new ArcadeScreenBounds(
                    -0.333333F,
                    1.333333F,
                    -0.333333F,
                    1.333333F,
                    0.20F,
                    1.45F,
                    0.228125F);
            case WATERFRAMES_TV_BOX -> new ArcadeScreenBounds(
                    0.166667F,
                    0.833333F,
                    0.166667F,
                    0.833333F,
                    0.375F,
                    0.875F,
                    0.0F);
            case DELUXE -> new ArcadeScreenBounds(
                    0.125F,
                    0.875F,
                    0.125F,
                    0.875F,
                    0.328125F,
                    0.890625F,
                    0.0F);
            case PORTRAIT_CABINET -> portraitBounds();
            case LEGACY_GENERIC -> rocketScreenBounds();
            case HOME_RETRO_TV -> homeTvScreenBounds();
            case HOME_LCD_TV, HOME_WIDE_LCD_TV, HOME_LARGE_LCD_TV, HOME_VINTAGE_TV, DUAL_CABINET,
                 HOME_GRAY_CRT,HOME_RED_CRT,HOME_PANEL_2,HOME_PANEL_2_WALL,HOME_PANEL_3,HOME_PANEL_3_WALL -> throw new IllegalStateException("Handled above");
            case CLASSIC -> new ArcadeScreenBounds(
                    0.10F,
                    0.90F,
                    0.10F,
                    0.90F,
                    0.125F,
                    0.875F,
                    0.0F);
            case WATERFRAMES_PANEL ->
                    throw new IllegalStateException("Handled above");
        };
    }

    private static ArcadeScreenBounds portraitBounds(){
        var q=ScreenAspectFit.fit(PortraitCabinetGeometry.screen(0),4D/3);
        return new ArcadeScreenBounds((float)q.lowerMinX().x(),(float)q.lowerMaxX().x(),(float)q.lowerMinX().x(),(float)q.lowerMaxX().x(),(float)q.lowerMinX().y(),(float)q.upperMinX().y(),(float)q.center().z());
    }
    private static ArcadeScreenBounds homeTvScreenBounds() {
        float scale = (float) cn.piq.fcarcade.home.HomeHardwareScale.TV_SCALE;
        float left = 1.88F * scale / 16.0F;
        float right = 14.12F * scale / 16.0F;
        // North extends toward +X from the anchor. South/West are reflected
        // around the original anchor block center, NOT the 2x2 body's center.
        return new ArcadeScreenBounds(1.0F - right, right, left, 1.0F - left,
                2.4F * scale / 16.0F, 11.58F * scale / 16.0F,
                0.407F * scale / 16.0F);
    }

    private static ArcadeScreenBounds rocketScreenBounds() {
        var screen = RocketArcadeGeometry.screen(0);
        // This is the axis-aligned projection. Use the shared quad for the
        // sloping plane's actual height, aspect ratio and per-corner depth.
        float left = (float) screen.lowerMinX().x();
        float right = (float) screen.lowerMaxX().x();
        return new ArcadeScreenBounds(left, right, left, right,
                (float) screen.lowerMinX().y(), (float) screen.upperMinX().y(),
                (float) screen.center().z());
    }

    private static ArcadeScreenBounds dualScreenBounds() {
        // Final sloped quad is supplied by DualCabinetGeometry; these projected bounds
        // also give menus/occupancy the correct cabinet dimensions.
        var screen = DualCabinetGeometry.screen(0);
        float left = (float) screen.lowerMinX().x();
        float right = (float) screen.lowerMaxX().x();
        return new ArcadeScreenBounds(left, right, left, right,
                (float) screen.lowerMinX().y(), (float) screen.upperMinX().y(), (float) screen.center().z());
    }

    private static ArcadeScreenBounds fitted(
            int width,
            int height,
            float margin,
            float frontInset
    ) {
        float availableWidth = width - margin * 2.0F;
        float availableHeight = height - margin * 2.0F;
        float contentWidth = availableWidth;
        float contentHeight = contentWidth / NES_ASPECT;
        if (contentHeight > availableHeight) {
            contentHeight = availableHeight;
            contentWidth = contentHeight * NES_ASPECT;
        }
        float horizontalStart = (width - contentWidth) * 0.5F;
        float horizontalEnd = horizontalStart + contentWidth;
        float bottom = (height - contentHeight) * 0.5F;
        return new ArcadeScreenBounds(
                horizontalStart,
                1.0F - horizontalStart,
                1.0F - horizontalEnd,
                horizontalEnd,
                bottom,
                bottom + contentHeight,
                frontInset);
    }

    public float aspectRatio() {
        return (positiveMax - min) / (top - bottom);
    }
}
