package cn.piq.gba.client;

/** Pure geometry used by item rendering, articulated controls and offline QA. Source units remain untouched. */
public final class GbaHandheldLayout {
    private GbaHandheldLayout(){}
    public static final double CENTER_X=8,CENTER_Y=.7215,CENTER_Z=8.021;
    public static final double SCREEN_Y=1.302,SCREEN_X0=6.2,SCREEN_X1=9.8,SCREEN_Z0=6.61,SCREEN_Z1=9.01;
    public enum View { FIRST,THIRD,GUI,GROUND,FIXED }
    public record Point(double x,double y,double z){}
    public record Pose(double x,double y,double z,double yaw,double pitch,double roll,double scale){}
    public record Arm(double x,double y,double z,double pitch,double roll,double scale){}
    public record Motion(double x,double y,double z,double pitch,double roll,Point pivot){}
    public static Pose item(View view){return switch(view){
        case FIRST->new Pose(.5,.5,.5,0,90,0,1.2);
        case THIRD->new Pose(.5,.5,.5,0,90,0,1);
        case GUI->new Pose(.5,.50,.5,-12,75,0,1.6);
        case GROUND->new Pose(.5,.07,.5,0,0,0,.9);
        case FIXED->new Pose(.5,.5,.5,0,90,0,1.3);
    };}
    public static Pose first(boolean right,boolean two,double equip,double swing){
        equip=finite(equip);swing=finite(swing);double wave=Math.sin(Math.sqrt(Math.clamp(swing,0,1))*Math.PI);
        return new Pose(two?0:right?.34:-.34,-.45-.45*Math.clamp(equip,0,1)+.018*wave,-1.10-.03*wave,0,-20+4*wave,0,1);
    }
    public static Arm arm(boolean right){
        double side=right?1:-1,scale=.80,pitch=Math.toRadians(30),roll=Math.toRadians(side*60);
        double hx=-side*6/16.0,hy=Math.cos(pitch)*11/16.0,hz=Math.sin(pitch)*11/16.0;
        double x=Math.cos(roll)*hx-Math.sin(roll)*hy,y=Math.sin(roll)*hx+Math.cos(roll)*hy;
        return new Arm(side*.29-scale*x,-.09-scale*y,.015-scale*hz,30,side*60,scale);
    }
    public static Pose first(boolean right,boolean two,double equip,double swing,boolean raised){
        // Front-on 3:2 screen occupies ~38% of view height at the default hand FOV.
        return raised?new Pose(0,-.02,-.95,0,0,0,2.8):first(right,two,equip,swing);
    }
    public static boolean eligible(boolean item,boolean alive,boolean using,boolean invisible,boolean scoping,boolean swimming,boolean flying){return item&&alive&&!using&&!invisible&&!scoping&&!swimming&&!flying;}
    public static Point screen(int corner){return switch(corner){
        case 0->new Point(SCREEN_X0,SCREEN_Y,SCREEN_Z0);case 1->new Point(SCREEN_X0,SCREEN_Y,SCREEN_Z1);
        case 2->new Point(SCREEN_X1,SCREEN_Y,SCREEN_Z1);case 3->new Point(SCREEN_X1,SCREEN_Y,SCREEN_Z0);default->throw new IllegalArgumentException();
    };}
    public static Motion motion(String part,int mask){
        int bit;double press=.045;Point pivot;
        switch(part){
            case "dpad"->{pivot=new Point(5,1.265,7.44);double pitch=((mask&(1<<4))!=0?-2:0)+((mask&(1<<5))!=0?2:0),roll=((mask&(1<<6))!=0?2:0)+((mask&(1<<7))!=0?-2:0);return new Motion(0,(mask&0xf0)==0?0:-press,0,pitch,roll,pivot);}
            case "button_a"->{bit=8;pivot=new Point(11.58,1.265,7.15);}
            case "button_b"->{bit=0;pivot=new Point(10.92,1.265,7.67);}
            case "button_select"->{bit=2;press=.03;pivot=new Point(4.86,1.265,8.78);}
            case "button_start"->{bit=3;press=.03;pivot=new Point(4.86,1.265,9.36);}
            case "shoulder_l"->{bit=10;pivot=new Point(4.835,1.265,5.86);}
            case "shoulder_r"->{bit=11;pivot=new Point(11.165,1.265,5.86);}
            default->{return new Motion(0,0,0,0,0,new Point(0,0,0));}
        }
        return new Motion(0,(mask&(1<<bit))==0?0:-press,0,0,0,pivot);
    }
    public static Point point(Point source,Pose pose){
        return transform(new Point((source.x-CENTER_X)/16,(source.y-CENTER_Y)/16,(source.z-CENTER_Z)/16),pose);
    }
    public static Point transform(Point point,Pose pose){
        double x=point.x*pose.scale,y=point.y*pose.scale,z=point.z*pose.scale;
        double c=Math.cos(Math.toRadians(pose.roll)),s=Math.sin(Math.toRadians(pose.roll)),ax=c*x-s*y,ay=s*x+c*y;
        c=Math.cos(Math.toRadians(pose.pitch));s=Math.sin(Math.toRadians(pose.pitch));double by=c*ay-s*z,bz=s*ay+c*z;
        c=Math.cos(Math.toRadians(pose.yaw));s=Math.sin(Math.toRadians(pose.yaw));return new Point(pose.x+c*ax+s*bz,pose.y+by,pose.z-s*ax+c*bz);
    }
    private static double finite(double v){return Double.isFinite(v)?v:0;}
}
