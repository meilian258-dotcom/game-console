package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;

/** Outer item/arm transforms only. The supplied model units and UVs stay untouched. */
public final class ZapperPoseLayout {
    public static final Point GRIP=new Point(10.10,2.90,8.204);
    public static final Point TRIGGER_PIVOT=new Point(9.52327672,4.03390858,8.204);
    public static final double TRIGGER_DEGREES=8;
    public static final long TRIGGER_TRAVEL_NANOS=70_000_000L;
    public enum View { FIRST_RIGHT,FIRST_LEFT,THIRD_RIGHT,THIRD_LEFT,GUI,GROUND,FIXED }
    public record ItemPose(double x,double y,double z,double yaw,double pitch,double roll,double scale,Point origin) {}
    public record FirstPose(double x,double y,double z) {}
    public record ArmPose(double pitch,double yaw,double roll) {}
    private ZapperPoseLayout() {}
    public static ItemPose item(View view) {
        return switch(view) {
            case FIRST_RIGHT,FIRST_LEFT,THIRD_RIGHT,THIRD_LEFT -> new ItemPose(.5,.5,.5,-90,0,0,1,GRIP);
            case GUI -> new ItemPose(.5,.48,.5,210,20,0,1.65,new Point(8,3.65,8.204));
            case GROUND -> new ItemPose(.5,.20,.5,0,0,0,.75,new Point(8,1.8,8.204));
            case FIXED -> new ItemPose(.5,.5,.5,180,0,0,1,new Point(8,3.65,8.204));
        };
    }
    public static FirstPose first(boolean right,float equip) {
        double lowered=Math.clamp(Float.isFinite(equip)?equip:0,0,1);
        return new FirstPose(right?.38:-.38,-.42-.35*lowered,-.62);
    }
    public static ArmPose arm(boolean right,double headPitch,double headYaw) {
        if (!Double.isFinite(headPitch)||!Double.isFinite(headYaw)) return new ArmPose(0,0,0);
        return new ArmPose(-Math.PI/2+Math.clamp(headPitch,-Math.PI/2,Math.PI/2),
                Math.clamp(headYaw,-Math.PI/2,Math.PI/2),right?-.1:.1);
    }
    public static double advanceTrigger(double previous,boolean pressed,long elapsedNanos) {
        double start=Math.clamp(Double.isFinite(previous)?previous:0,0,1);
        double delta=Math.clamp(elapsedNanos,0,TRIGGER_TRAVEL_NANOS)/(double)TRIGGER_TRAVEL_NANOS;
        return Math.clamp(start+(pressed?delta:-delta),0,1);
    }
    public static Point itemPoint(Point source,ItemPose pose) {
        double x=(source.x()-pose.origin().x())/16*pose.scale(),y=(source.y()-pose.origin().y())/16*pose.scale(),z=(source.z()-pose.origin().z())/16*pose.scale();
        double c=Math.cos(Math.toRadians(pose.roll())),s=Math.sin(Math.toRadians(pose.roll()));double ax=c*x-s*y,ay=s*x+c*y;
        c=Math.cos(Math.toRadians(pose.pitch()));s=Math.sin(Math.toRadians(pose.pitch()));double by=c*ay-s*z,bz=s*ay+c*z;
        c=Math.cos(Math.toRadians(pose.yaw()));s=Math.sin(Math.toRadians(pose.yaw()));
        return new Point(pose.x()+c*ax+s*bz,pose.y()+by,pose.z()-s*ax+c*bz);
    }
    public static Point triggerPoint(Point source,double progress) {
        double a=Math.toRadians(TRIGGER_DEGREES*Math.clamp(Double.isFinite(progress)?progress:0,0,1));
        double x=source.x()-TRIGGER_PIVOT.x(),y=source.y()-TRIGGER_PIVOT.y();
        return new Point(TRIGGER_PIVOT.x()+Math.cos(a)*x-Math.sin(a)*y,
                TRIGGER_PIVOT.y()+Math.sin(a)*x+Math.cos(a)*y,source.z());
    }
}
