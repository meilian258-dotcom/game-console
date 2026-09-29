package cn.piq.sfchome.layout;

import cn.piq.fcarcade.home.ApplianceRay;
import cn.piq.fcarcade.home.HomeApplianceControl;
import net.minecraft.world.phys.Vec3;
import static cn.piq.fcarcade.home.HomeApplianceControl.*;

/** Same scale and dock translation as the immutable user mesh. */
public final class SfcApplianceControls {
    private SfcApplianceControls() {}
    private static ApplianceRay.Box console(HomeApplianceControl action,double x0,double y0,double z0,double x1,double y1,double z1) {
        var a = SfcConsoleScale.console(x0,y0,z0); var b = SfcConsoleScale.console(x1,y1,z1);
        return ApplianceRay.units(action,a.x()-.08,a.y()-.04,a.z()-.08,b.x()+.08,b.y()+.04,b.z()+.08);
    }
    private static ApplianceRay.Box pad(int port) {
        var b = SfcConsoleScale.pad(port,0);
        return ApplianceRay.units(port==0 ? CONTROLLER_ONE : CONTROLLER_TWO,b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
    }
    private static final ApplianceRay.Box[] BUTTONS = {
        console(POWER,9.555,2.29,8.1625,10.465,2.505,9.0625),
        console(RESET,5.534999996,2.29,8.1625,6.445,2.4885,9.0625),pad(0),pad(1)
    };
    public static HomeApplianceControl hit(Vec3 eye,Vec3 end) {
        return hitPoints(new ApplianceRay.Point(eye.x,eye.y,eye.z),new ApplianceRay.Point(end.x,end.y,end.z));
    }
    public static HomeApplianceControl hitPoints(ApplianceRay.Point eye,ApplianceRay.Point end) {
        if(eye.z()>=17.9/16 && ApplianceRay.pick(eye,end,
                ApplianceRay.units(VIDEO_DISCONNECT,3.5,.40,17.7,5.15,1.8,19.1))==VIDEO_DISCONNECT)return VIDEO_DISCONNECT;
        var picked = ApplianceRay.pick(eye,end,BUTTONS);
        if ((picked==POWER || picked==RESET) && eye.y() < 3.435/16) return NONE;
        return picked;
    }
}
