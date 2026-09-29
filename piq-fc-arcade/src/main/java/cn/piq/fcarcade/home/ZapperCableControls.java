package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.ZapperStandGeometry;

/** Small physical unplug targets, sharing the renderer's exact cable endpoints. */
final class ZapperCableControls {
    private ZapperCableControls() {}

    static boolean console(ControllerCableGeometry.Style style, ApplianceRay.Point eye, ApplianceRay.Point end) {
        if (style == null || style == ControllerCableGeometry.Style.SFC || eye == null || end == null) return false;
        var socket = ControllerCableGeometry.socket(style, 1, 0);
        // Never select the rear cable through a front button, cartridge or controller well.
        return eye.z() > socket.z() && hit(eye, end, socket.x(), socket.y(), socket.z(), .105, .085, .12);
    }

    static boolean stand(ApplianceRay.Point eye, ApplianceRay.Point end) {
        var socket = ZapperStandGeometry.CORD;
        // The cord exits the right side; aiming at the gun itself still borrows it.
        return eye != null && end != null && eye.x() > socket.x()
                && hit(eye, end, socket.x(), socket.y(), socket.z(), .14, .075, .10);
    }

    private static boolean hit(ApplianceRay.Point eye, ApplianceRay.Point end,
                               double x, double y, double z, double dx, double dy, double dz) {
        var box = new ApplianceRay.Box(HomeApplianceControl.NONE,
                new ApplianceRay.Point(x - dx, Math.max(0, y - dy), z - dz),
                new ApplianceRay.Point(x + dx, y + dy, z + dz));
        return Double.isFinite(ApplianceRay.intersection(eye, end, box));
    }
}
