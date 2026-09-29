package cn.piq.fcarcade.client;

/** Controller-only pose constants in camera/model units; no Minecraft state or I/O. */
final class ControllerPoseLayout {
    static final double THIRD_ARM_PITCH = -Math.PI / 3;
    static final double THIRD_ARM_INWARD_ROLL = Math.toRadians(38);
    static final double SINGLE_ARM_YAW = Math.toRadians(40);
    // Canonical +Z is the button-face normal. A -60 degree rig pitch leaves
    // the face 30 degrees above horizontal: low, upward-facing, with visible
    // key travel. Both arms share this rotation; their local grips stay fixed.
    static final double FIRST_PITCH = -60;
    static final double FIRST_ARM_SCALE = .82;
    static final double FIRST_ARM_PITCH = 30;
    static final double FIRST_ARM_ROLL = 60;
    static final double DEFAULT_HAND_SIZE = 1.18;
    // Shared by the item and both first-person arms. Lower the whole rig and
    // retreat enough to preserve the full controller even at the water hand FOV.
    static final double FIRST_IDLE_Y = -.62;
    static final double FIRST_TWO_HAND_Z = -1.46;
    static final double FIRST_MIXED_Z = -1.58;
    record First(double x, double y, double z, double pitch, double yaw) {}
    record Arm(double x, double y, double z, double pitch, double roll, double scale) {}
    private ControllerPoseLayout() {}

    static boolean eligible(boolean controller, boolean player, boolean alive, boolean usingItem, boolean swimming, boolean flying) {
        return controller && player && alive && !usingItem && !swimming && !flying;
    }
    static boolean twoHands(boolean eligible, boolean otherEmpty) { return eligible && otherEmpty; }
    static First first(boolean right, boolean twoHands, double equip, double swing) {
        equip = Math.clamp(equip, 0, 1);
        swing = Math.clamp(swing, 0, 1);
        double wave = Math.sin(Math.sqrt(swing) * Math.PI), side = right ? 1 : -1;
        return new First(twoHands ? 0 : side * .34, FIRST_IDLE_Y - .45 * equip + .018 * wave,
                (twoHands ? FIRST_TWO_HAND_Z : FIRST_MIXED_Z) - .03 * wave, FIRST_PITCH + 4 * wave, side * 2 * wave);
    }
    static Arm firstArm(boolean right) {
        return firstArm(right,DEFAULT_HAND_SIZE);
    }
    static Arm firstArm(boolean right,double size) {
        if(!Double.isFinite(size))size=DEFAULT_HAND_SIZE;
        size=Math.clamp(size,1,1.30);
        double side = right ? 1 : -1;
        // Preserve the previous distal grip exactly, then rotate the forearms
        // below/outside the upward-facing controller. Merely pitching the old
        // upright wrist rig makes wide sleeves cover the A button and D-pad.
        double pitch=Math.toRadians(-38),roll=Math.toRadians(side*24);
        double hx=-side*6/16.0,hy=Math.cos(pitch)*11/16.0,hz=Math.sin(pitch)*11/16.0;
        double rx=Math.cos(roll)*hx-Math.sin(roll)*hy,ry=Math.sin(roll)*hx+Math.cos(roll)*hy;
        double nextPitch=Math.toRadians(FIRST_ARM_PITCH),nextRoll=Math.toRadians(side*FIRST_ARM_ROLL);
        double ny=Math.cos(nextPitch)*11/16.0,nz=Math.sin(nextPitch)*11/16.0;
        double nx=Math.cos(nextRoll)*hx-Math.sin(nextRoll)*ny;
        double yr=Math.sin(nextRoll)*hx+Math.cos(nextRoll)*ny;
        return new Arm(side*.80+FIRST_ARM_SCALE*(rx-size*nx),
                -.36+FIRST_ARM_SCALE*(ry-size*yr),.38+FIRST_ARM_SCALE*(hz-size*nz),
                FIRST_ARM_PITCH,side*FIRST_ARM_ROLL,FIRST_ARM_SCALE*size);
    }
}
