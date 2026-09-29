package cn.piq.sfchome.client;

/** Pure layer-selection contract; all meshes already contain their reviewed native coordinates. */
public final class SfcModelPresentation {
    public static final int BODY = 0, P1 = 1, P2 = 2, EMPTY_SLOT = 3, CARTRIDGE = 4;
    public static final int MODEL_COUNT = 5;
    private static final String[] PATHS = {"block/sfc_console_body", "block/sfc_console_controller_1",
            "block/sfc_console_controller_2", "block/sfc_console_slot_cover", "block/sfc_console_cartridge_inserted"};
    private SfcModelPresentation() {}

    public static String path(int index) {
        if (index < 0 || index >= MODEL_COUNT) throw new IllegalArgumentException("Unknown SFC hardware layer");
        return PATHS[index];
    }

    public static boolean visible(int index, boolean p1Docked, boolean p2Docked, boolean hasCartridge) {
        return switch (index) {
            case BODY -> true;
            case P1 -> p1Docked;
            case P2 -> p2Docked;
            case EMPTY_SLOT -> !hasCartridge;
            case CARTRIDGE -> hasCartridge;
            default -> throw new IllegalArgumentException("Unknown SFC hardware layer");
        };
    }

    /** Minecraft clockwise horizontal block-state y=90 is an Axis.YP -90 pose. */
    public static float yawDegrees(int turns) { return -90 * Math.floorMod(turns, 4); }
}
