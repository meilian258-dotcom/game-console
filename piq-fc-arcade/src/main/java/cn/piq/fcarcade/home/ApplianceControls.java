package cn.piq.fcarcade.home;

import static cn.piq.fcarcade.home.HomeApplianceControl.*;
import static cn.piq.fcarcade.home.ApplianceRay.units;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;

/** Reviewed exported geometry, already scaled; never apply the old scale a second time. */
public final class ApplianceControls {
    private ApplianceControls() {}
    private static final ApplianceRay.Box[] FC = {
        units(POWER, 9.94,3.32,3.45,10.94,3.61,4.33),
        units(RESET, 4.98,3.28,3.37,6.14,3.54,4.46),
        units(CONTROLLER_ONE,11.94,1.20,5.30,13.55,4.68,13.30),
        units(CONTROLLER_TWO,2.45,1.20,5.30,4.06,4.68,13.30)
    };
    public static HomeApplianceControl famicom(ApplianceRay.Point eye, ApplianceRay.Point end) {
        // Only reachable from behind: never press the socket through the front case.
        if(eye.z()>=14.66/16 && ApplianceRay.pick(eye,end,units(VIDEO_DISCONNECT,7.2,1.48,14.50,8.8,3.08,16.1))==VIDEO_DISCONNECT)
            return VIDEO_DISCONNECT;
        HomeApplianceControl picked = ApplianceRay.pick(eye,end,FC);
        if ((picked == POWER || picked == RESET) && eye.y() < 3.28/16) return NONE;
        return picked;
    }
    public static HomeApplianceControl subor(boolean wide, ApplianceRay.Point eye, ApplianceRay.Point end) {
        return subor(wide,false,eye,end);
    }
    public static HomeApplianceControl subor(boolean wide, boolean compact, ApplianceRay.Point eye, ApplianceRay.Point end) {
        if (wide && compact) {
            eye = new ApplianceRay.Point(eye.x(),eye.y(),(eye.z()+4.0/16)/.8);
            end = new ApplianceRay.Point(end.x(),end.y(),(end.z()+4.0/16)/.8);
        }
        double rear=wide?23.632:12.68;
        if(eye.z()>=rear/16 && ApplianceRay.pick(eye,end,wide
                ?units(VIDEO_DISCONNECT,20.62,.30,23.25,24.92,1.80,24.9)
                :units(VIDEO_DISCONNECT,9.65,.05,12.35,13.37,1.10,13.8))==VIDEO_DISCONNECT)return VIDEO_DISCONNECT;
        HomeApplianceControl picked = !wide ? ApplianceRay.pick(eye,end,
            units(POWER,1.36,.67,7.57,1.90,.84,8.11),
            units(RESET,2.02,.67,7.57,2.56,.88,8.11),
            units(CONTROLLER_ONE,10.02,.07,3.36,13.18,.50,4.98),
            units(CONTROLLER_TWO,2.82,.07,3.36,5.98,.50,4.98)) : ApplianceRay.pick(eye,end,
            units(POWER,4.98,2.67,19.66,5.74,2.95,20.42),
            units(RESET,6.12,2.67,19.66,6.88,3.01,20.42),
            units(CONTROLLER_ONE,18.33,.12,6.49,26.07,1.25,10.51),
            units(CONTROLLER_TWO,5.93,.12,6.49,13.67,1.25,10.51));
        if ((picked == POWER || picked == RESET) && eye.y() < (wide ? 2.67 : .67)/16) return NONE;
        return picked;
    }
    public static HomeApplianceControl television(ArcadeDisplayStyle style, ApplianceRay.Point eye, ApplianceRay.Point end) {
        if (UserTvLayout.supports(style)) {
            if (UserTvLayout.panel(style)) {
                double dx=UserTvLayout.width(style)==3?16:0, dz=UserTvLayout.wall(style)?4:0;
                if(eye.z()>(7.49+dz)/16)return NONE;
                return ApplianceRay.pick(eye,end,
                    // Front centre bezel: reachable without clicking through the shell
                    // to the original underside controls.
                    units(POWER,UserTvLayout.width(style)*8-1.3,2.80,6.82+dz,UserTvLayout.width(style)*8+1.3,3.86,7.15+dz),
                    units(POWER,28.55+dx,2.77,7.45+dz,29.42+dx,3.22,8.04+dz),
                    units(VOLUME_UP,27.55+dx,2.77,7.45+dz,28.42+dx,3.22,8.04+dz),
                    units(VOLUME_DOWN,26.55+dx,2.77,7.45+dz,27.42+dx,3.22,8.04+dz));
            }
            if(style==ArcadeDisplayStyle.HOME_GRAY_CRT) {
                if(eye.z()>.82/16)return NONE;
                return ApplianceRay.pick(eye,end,units(POWER,.86,.9,.73,1.72,1.73,1.26),
                    units(VOLUME_DOWN,6.00,1.03,.78,6.63,1.47,1.2),units(VOLUME_UP,6.96,1.03,.78,7.59,1.47,1.2));
            }
            if(eye.z()>1.15/16)return NONE;
            return ApplianceRay.pick(eye,end,units(POWER,1.34,3.23,1.1,2.28,3.87,1.57),
                units(VOLUME_UP,1.28,8.80,.9,2.44,9.32,1.65),units(VOLUME_DOWN,1.28,8.24,.9,2.44,8.78,1.65));
        }
        // The front buttons cannot be pressed through the back of a television.
        if (style == ArcadeDisplayStyle.HOME_RETRO_TV) {
            if (eye.z() > 1.5/16) return NONE;
            return ApplianceRay.pick(eye,end,
                units(POWER,1.75,1.85,1.40,3.40,3.42,2.55),
                units(VOLUME_UP,13.96,2.10,1.54,15.14,2.91,2.35),
                units(VOLUME_DOWN,12.04,2.10,1.54,13.22,2.91,2.35));
        }
        if (style == ArcadeDisplayStyle.HOME_VINTAGE_TV) {
            if (eye.z() > 1.8/16) return NONE;
            return ApplianceRay.pick(eye,end,
                units(POWER,1.40,1.24,1.91,2.22,1.78,2.20),
                units(VOLUME_UP,1.72,6.64,1.72,2.84,7.20,2.13),
                units(VOLUME_DOWN,1.72,6.06,1.72,2.84,6.63,2.13));
        }
        if (style == ArcadeDisplayStyle.HOME_LCD_TV || style == ArcadeDisplayStyle.HOME_LARGE_LCD_TV
                || style == ArcadeDisplayStyle.HOME_WIDE_LCD_TV) {
            if (eye.z() > 5.8/16) return NONE;
            double dx = style == ArcadeDisplayStyle.HOME_LCD_TV ? 0 : 8;
            return ApplianceRay.pick(eye,end,
                units(POWER,13.60+dx,1.06,5.76,14.50+dx,1.46,5.94),
                units(VOLUME_UP,12.40+dx,1.06,5.76,13.20+dx,1.46,5.94),
                units(VOLUME_DOWN,11.20+dx,1.06,5.76,12.00+dx,1.46,5.94));
        }
        return NONE;
    }
}
