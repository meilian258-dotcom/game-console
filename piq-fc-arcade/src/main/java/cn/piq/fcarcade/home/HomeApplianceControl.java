package cn.piq.fcarcade.home;

/** Physical controls shared by console providers and the crosshair hint. */
public enum HomeApplianceControl {
    NONE, POWER, RESET, CONTROLLER_ONE, CONTROLLER_TWO, VOLUME_UP, VOLUME_DOWN, VIDEO_DISCONNECT;
    public int port() { return this == CONTROLLER_ONE ? 0 : this == CONTROLLER_TWO ? 1 : -1; }
}
