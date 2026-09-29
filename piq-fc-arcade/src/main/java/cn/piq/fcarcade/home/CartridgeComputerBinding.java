package cn.piq.fcarcade.home;

import java.util.UUID;

/** Pure workstation-lease policy. A matching UUID never replaces the live BE object check. */
public record CartridgeComputerBinding(UUID computerId, String dimension, int x, int y, int z) {
    public static final double MAX_DISTANCE_SQUARED = 25.0;
    public CartridgeComputerBinding {
        if (computerId == null || computerId.equals(new UUID(0, 0)) || dimension == null || dimension.isBlank())
            throw new IllegalArgumentException("卡带电脑身份无效");
    }
    public boolean permits(UUID currentId, String currentDimension, boolean sameInstance, boolean loaded,
                           boolean alive, boolean contentAuthorized, boolean interactionAllowed, double distanceSquared) {
        return computerId.equals(currentId) && dimension.equals(currentDimension) && sameInstance && loaded
                && alive && contentAuthorized && interactionAllowed && Double.isFinite(distanceSquared)
                && distanceSquared >= 0 && distanceSquared <= MAX_DISTANCE_SQUARED;
    }
    public static boolean permitsPlayersSetting(int players, boolean romExists, boolean busy) {
        return (players == 1 || players == 2) && romExists && !busy;
    }
    /** A setting cannot quietly target a selected-but-unwritten or hidden different ROM. */
    public static boolean permitsSaveModeSetting(int mode,String requestedRom,String writtenRom,boolean romExists,boolean busy){
        return mode>=0&&mode<=2&&requestedRom!=null&&requestedRom.matches("[0-9a-f]{64}")
                &&requestedRom.equals(writtenRom)&&romExists&&!busy;
    }
}
