package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import cn.piq.fcarcade.layout.ScreenAspectFit;

/** Physical coordinates from the 20260917 user models, before vanilla JSON's X shift. */
public final class UserTvLayout {
    // Keep live/idle pixels clear of the baked black glass, including oblique views.
    private static final double SCREEN_DEPTH_GAP_BLOCKS = .002;
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private UserTvLayout() {}
    public static boolean supports(ArcadeDisplayStyle style) {
        return switch(style) {case HOME_GRAY_CRT,HOME_RED_CRT,HOME_PANEL_2,HOME_PANEL_2_WALL,HOME_PANEL_3,HOME_PANEL_3_WALL -> true; default -> false;};
    }
    public static boolean panel(ArcadeDisplayStyle style) {return supports(style)&&style!=ArcadeDisplayStyle.HOME_GRAY_CRT&&style!=ArcadeDisplayStyle.HOME_RED_CRT;}
    public static boolean wall(ArcadeDisplayStyle style) {return style==ArcadeDisplayStyle.HOME_PANEL_2_WALL||style==ArcadeDisplayStyle.HOME_PANEL_3_WALL;}
    public static int width(ArcadeDisplayStyle style) {return style==ArcadeDisplayStyle.HOME_PANEL_3||style==ArcadeDisplayStyle.HOME_PANEL_3_WALL?3:panel(style)?2:1;}
    public static Bounds bounds(ArcadeDisplayStyle style,int turns) {
        require(style);
        double x0=panel(style)?1:0,x1=panel(style)?width(style)*16-1:16;
        double y0=wall(style)?2.87:0, y1=panel(style)?(width(style)==3?29.4875:20.4875):style==ArcadeDisplayStyle.HOME_GRAY_CRT?15.75:22.45;
        double z0=panel(style)?(wall(style)?10.87:4.47):style==ArcadeDisplayStyle.HOME_GRAY_CRT?.399:.91;
        double z1=panel(style)?(wall(style)?16:10.96):style==ArcadeDisplayStyle.HOME_GRAY_CRT?14.455:13.46;
        var a=point(x0,y0,z0,turns);var b=point(x1,y1,z1,turns);
        return new Bounds(Math.min(a.x(),b.x())*16,y0,Math.min(a.z(),b.z())*16,Math.max(a.x(),b.x())*16,y1,Math.max(a.z(),b.z())*16);
    }
    public static Point socket(ArcadeDisplayStyle style,int turns,int channel) {
        require(style);if(channel<0||channel>2)throw new IllegalArgumentException("AV channel");
        if(panel(style))return point(22.8+(width(style)==3?16:0)+2.18*channel,5.91,wall(style)?13.51:9.51,turns);
        return point(6+1.43*channel,4.145,style==ArcadeDisplayStyle.HOME_GRAY_CRT?14.455:13.46,turns);
    }
    public static ScreenQuad screen(ArcadeDisplayStyle style,int turns) {
        require(style);
        double x0,x1,y0,y1,z;
        if(panel(style)){x0=1.73;x1=width(style)*16-1.73;y0=3.88;y1=width(style)==3?28.9075:19.9075;z=wall(style)?11.015:7.015;}
        else if(style==ArcadeDisplayStyle.HOME_GRAY_CRT){x0=2.08;x1=13.92;y0=2.55;y1=11.43;z=.407;}
        else {x0=3.36;x1=13.94;y0=3.36;y1=12.44;z=1.12;}
        z -= SCREEN_DEPTH_GAP_BLOCKS * 16;
        var n=RocketArcadeGeometry.rotate(new Point(.5,0,-.5),turns);
        var q=new ScreenQuad(point(x0,y0,z,turns),point(x1,y0,z,turns),point(x1,y1,z,turns),point(x0,y1,z,turns),new Point(n.x()-.5,0,n.z()-.5));
        return ScreenAspectFit.fit(q,4D/3);
    }
    /** Baked 3-wide JSON shifts -16 on X to respect vanilla's coordinate limit. */
    public static Point modelOffset(ArcadeDisplayStyle style,int turns) {
        if(width(style)!=3)return new Point(0,0,0);
        var q=RocketArcadeGeometry.rotate(new Point(1.5,0,.5),turns);return new Point(q.x()-.5,0,q.z()-.5);
    }
    public static double[] lamp(ArcadeDisplayStyle style) {
        require(style);
        if(panel(style))return new double[]{2.05,3.28,wall(style)?10.867:6.867,2.23,3.45};
        return style==ArcadeDisplayStyle.HOME_GRAY_CRT?new double[]{1.16,1.79,.977,1.3,1.93}:new double[]{1.74,2.14,1.127,1.88,2.28};
    }
    private static Point point(double x,double y,double z,int turns) {return RocketArcadeGeometry.rotate(new Point(x/16,y/16,z/16),turns);}
    private static void require(ArcadeDisplayStyle style){if(!supports(style))throw new IllegalArgumentException("Not a 20260917 TV");}
}
