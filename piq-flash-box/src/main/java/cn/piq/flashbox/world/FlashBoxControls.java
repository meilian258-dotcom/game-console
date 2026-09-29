package cn.piq.flashbox.world;

import cn.piq.fcarcade.home.ApplianceRay;
import cn.piq.fcarcade.home.HomeApplianceControl;
import net.minecraft.world.phys.Vec3;

/** The case buttons open a local menu, not shared power or two-player grants. */
public final class FlashBoxControls {
    private FlashBoxControls() {}
    public static HomeApplianceControl hit(Vec3 eye, Vec3 end) {
        if (eye.z() < 13.0 / 16) return HomeApplianceControl.NONE;
        return ApplianceRay.pick(new ApplianceRay.Point(eye.x, eye.y, eye.z),
                new ApplianceRay.Point(end.x, end.y, end.z),
                ApplianceRay.units(HomeApplianceControl.VIDEO_DISCONNECT, 6.5, .4, 12.8, 9.5, 2.6, 14.4));
    }
}
