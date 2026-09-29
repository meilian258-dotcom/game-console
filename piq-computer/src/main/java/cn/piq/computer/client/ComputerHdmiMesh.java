package cn.piq.computer.client;
import cn.piq.computer.ComputerGeometry;
import cn.piq.fcarcade.client.*;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.client.HomeAvCableMesh.Quad;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** HDMI centers measured from the GPU and existing television models, not the AV sockets. */
public final class ComputerHdmiMesh {
    public static Vec3 rotate(Vec3 p,int turns){var v=HomeHardwareRenderLayout.rotate(new Point(p.x,p.y,p.z),turns);return new Vec3(v.x(),v.y(),v.z());}
    public static Vec3 television(ArcadeDisplayStyle style,int turns){
        if(!UserTvLayout.panel(style))throw new IllegalArgumentException("No HDMI on this CRT model");
        return rotate(new Vec3((23.18+(UserTvLayout.width(style)==3?16:0))/16,7.735/16,(UserTvLayout.wall(style)?13.4:9.4)/16),turns);
    }
    private static Vec3 outward(int t){return rotate(new Vec3(.5,0,1.5),t).subtract(.5,0,.5);}
    public static List<Quad> build(int pcTurns,ArcadeDisplayStyle style,int tvTurns,BlockPos delta){
        var a=rotate(ComputerGeometry.HDMI,pcTurns);
        if(!UserTvLayout.panel(style)){
            var lo=rotate(ComputerGeometry.units(2.4,0,.6),pcTurns);var hi=rotate(ComputerGeometry.units(13.4,22,15.4),pcTurns);
            return UserTvCableMesh.buildMultiOut(p(a),new UserTvCableMesh.Bounds(Math.min(lo.x,hi.x),0,Math.min(lo.z,hi.z),Math.max(lo.x,hi.x),.825,Math.max(lo.z,hi.z)),pcTurns,.36,style,tvTurns,delta.getX(),delta.getY(),delta.getZ());
        }
        var b=television(style,tvTurns).add(delta.getX(),delta.getY(),delta.getZ());var na=outward(pcTurns);var nb=outward(tvTurns);
        var out=new ArrayList<Quad>();var tailA=plug(out,a,na,.030,.013,.075);var tailB=plug(out,b,nb,.056,.021,.075);
        double floor=Math.min(a.y,b.y)>.20?Math.min(0,delta.getY())+.025:Math.min(a.y,b.y)-.04;
        var pa=tailA.add(na.scale(.16));var pb=tailB.add(nb.scale(UserTvLayout.wall(style)?.018:.16));
        var lowA=new Vec3(pa.x,floor,pa.z);var lowB=new Vec3(pb.x,floor,pb.z);
        curve(out,tailA,pa,lowA.add(0,.12,0),lowA);curve(out,lowA,lowA.lerp(lowB,.3).add(0,.035,0),lowA.lerp(lowB,.7).add(0,.035,0),lowB);curve(out,lowB,lowB.add(0,.12,0),pb,tailB);
        return List.copyOf(out);
    }
    private static Vec3 plug(List<Quad> out,Vec3 c,Vec3 n,double w,double h,double length){
        var right=new Vec3(n.z,0,-n.x);var up=new Vec3(0,1,0);var tail=c.add(n.scale(length));
        Vec3[] cross={right.scale(-w).add(up.scale(h)),right.scale(w).add(up.scale(h)),right.scale(w*.78).add(up.scale(-h)),right.scale(-w*.78).add(up.scale(-h))};
        for(int i=0;i<4;i++){var x=cross[i];var y=cross[(i+1)%4];add(out,c.add(x),tail.add(x),tail.add(y),c.add(y),x.add(y).normalize(),0x30343b);}
        add(out,tail.add(cross[0]),tail.add(cross[1]),tail.add(cross[2]),tail.add(cross[3]),n,0x25292e);return tail;
    }
    private static void curve(List<Quad> out,Vec3 a,Vec3 b,Vec3 c,Vec3 d){var last=a;for(int i=1;i<=24;i++){double t=i/24.0,u=1-t;var next=a.scale(u*u*u).add(b.scale(3*u*u*t)).add(c.scale(3*u*t*t)).add(d.scale(t*t*t));tube(out,last,next);last=next;}}
    private static void tube(List<Quad> out,Vec3 a,Vec3 b){var dir=b.subtract(a).normalize();var right=dir.cross(new Vec3(0,1,0));if(right.lengthSqr()<1e-9)right=dir.cross(new Vec3(1,0,0));right=right.normalize().scale(.012);var up=dir.cross(right).normalize().scale(.012);Vec3[] r={right,up,right.scale(-1),up.scale(-1)};for(int i=0;i<4;i++)add(out,a.add(r[i]),b.add(r[i]),b.add(r[(i+1)%4]),a.add(r[(i+1)%4]),r[i].add(r[(i+1)%4]).normalize(),0x191c21);}
    private static Point p(Vec3 v){return new Point(v.x,v.y,v.z);}
    private static void add(List<Quad> out,Vec3 a,Vec3 b,Vec3 c,Vec3 d,Vec3 n,int rgb){out.add(new Quad(p(a),p(b),p(c),p(d),p(n),rgb));}
}
