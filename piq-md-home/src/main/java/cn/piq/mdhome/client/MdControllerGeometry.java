// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

/** Read-only animation in the original MD model axes: face +Y, top +Z, left +X.
 * Cap meshes are derived by explicit exporter names, not by overlapping shell bounds. */
final class MdControllerGeometry {
    static final int BODY=0, DPAD=1, A=2, B=3, C=4, X=5, Y=6, Z=7, START=8, MODE=9, PARTS=10;
    static final java.util.List<String> NAMES=java.util.List.of("body","dpad","a","b","c","x","y","z","start","mode");
    static final double DPAD_X=9.42, DPAD_Y=8.27, DPAD_Z=7.5050959;
    record Transform(double y,double z,double pitch,double roll) {
        static final Transform REST=new Transform(0,0,0,0);
    }
    private MdControllerGeometry() {}
    /** Canonical SFC-profile bits before MdProfile.input remaps the core layout. */
    static int mask(int part) {
        return switch(part) { case A->1;case B->256;case C->2048;case X->2;case Y->512;
            case Z->1024;case START->8;case MODE->4;default->0; };
    }
    static double travel(int part) {
        return switch(part) { case A,B,C,X,Y,Z->.055;case START->.040;case MODE->.060;default->0; };
    }
    private static double bounded(double value) { return Double.isFinite(value)?Math.clamp(value,0,1):0; }
    static Transform sample(int part,double press,double up,double down,double left,double right) {
        if(part==DPAD) {
            double pitch=(bounded(up)-bounded(down))*5,roll=(bounded(right)-bounded(left))*5;
            return pitch==0&&roll==0?Transform.REST:new Transform(0,0,pitch,roll);
        }
        double depth=travel(part)*bounded(press);
        if(depth==0)return Transform.REST;
        // MODE is the real rear-edge cap, not an invented front button.
        return part==MODE?new Transform(0,-depth,0,0):new Transform(-depth,0,0,0);
    }
}
