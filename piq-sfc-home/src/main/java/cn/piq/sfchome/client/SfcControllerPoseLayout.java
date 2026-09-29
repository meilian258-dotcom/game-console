// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Camera-space pose shared by the real first-person render and offline geometry/hand QA. */
final class SfcControllerPoseLayout {
    static final double PITCH=-60,IDLE_Y=-.62,IDLE_Z=-1.46,CONTROLLER_SCALE=.90;
    record Rig(double y,double z,double pitch){}
    record Arm(double x,double y,double z,double pitch,double roll,double scale){}
    private SfcControllerPoseLayout(){}
    static boolean eligible(boolean controller,boolean otherEmpty,boolean alive,boolean invisible,boolean scoping,boolean swimming,boolean flying){return controller&&otherEmpty&&alive&&!invisible&&!scoping&&!swimming&&!flying;}
    static Rig rig(double equip,double swing){equip=Math.clamp(equip,0,1);swing=Math.clamp(swing,0,1);double wave=Math.sin(Math.sqrt(swing)*Math.PI);return new Rig(IDLE_Y-.45*equip+.018*wave,IDLE_Z-.03*wave,PITCH+4*wave);}
    static Arm arm(boolean right){
        double side=right?1:-1,scale=.82,size=1.18;
        double oldPitch=Math.toRadians(-38),oldRoll=Math.toRadians(side*24),hx=-side*6/16.0;
        double hy=Math.cos(oldPitch)*11/16.0,hz=Math.sin(oldPitch)*11/16.0;
        double rx=Math.cos(oldRoll)*hx-Math.sin(oldRoll)*hy,ry=Math.sin(oldRoll)*hx+Math.cos(oldRoll)*hy;
        double pitch=Math.toRadians(30),roll=Math.toRadians(side*60),ny=Math.cos(pitch)*11/16.0,nz=Math.sin(pitch)*11/16.0;
        double nx=Math.cos(roll)*hx-Math.sin(roll)*ny,yr=Math.sin(roll)*hx+Math.cos(roll)*ny;
        return new Arm(side*.80+scale*(rx-size*nx),-.36+scale*(ry-size*yr),.38+scale*(hz-size*nz),30,side*60,scale*size);
    }
}
