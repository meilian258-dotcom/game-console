package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.client.HomeAvCableMesh.Box;
import cn.piq.fcarcade.client.HomeAvCableMesh.Housing;
import cn.piq.fcarcade.client.HomeAvCableMesh.Quad;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.ArrayList;
import java.util.List;
import static cn.piq.fcarcade.client.HomeAvCableMesh.*;

/** Shared FC/SFC adapter for the user's new televisions. Old-TV geometry remains unchanged. */
public final class UserTvCableMesh {
    public record Bounds(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private static final int RUBBER=0x222426,METAL=0xA7A9A8;
    private static final double CABLE_RADIUS=.015;
    private UserTvCableMesh() {}

    public static List<Quad> buildFc(boolean subor,boolean wide,boolean compact,int consoleTurns,
                                     ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz) {
        return buildFc(subor,wide,compact,consoleTurns,tv,tvTurns,dx,dy,dz,HomeAvCableLayout.routeSeed(consoleTurns,tvTurns,dx,dy,dz));
    }
    public static List<Quad> buildFc(boolean subor,boolean wide,boolean compact,int consoleTurns,
                                     ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz,long seed) {
        return build(consoleSockets(subor,wide,compact,consoleTurns),consoleHousing(subor,wide,compact,consoleTurns),
                consoleTurns,subor&&!wide?.48:1,!subor,false,tv,tvTurns,dx,dy,dz,seed);
    }

    /** SFC supplies its already-rotated MULTI OUT endpoint and housing; no addon class is loaded by FC. */
    public static List<Quad> buildMultiOut(Point socket,Bounds bounds,int consoleTurns,double scale,
                                           ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz) {
        return buildMultiOut(socket,bounds,consoleTurns,scale,tv,tvTurns,dx,dy,dz,HomeAvCableLayout.routeSeed(consoleTurns,tvTurns,dx,dy,dz));
    }
    public static List<Quad> buildMultiOut(Point socket,Bounds bounds,int consoleTurns,double scale,
                                           ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz,long seed) {
        if(socket==null||bounds==null||!Double.isFinite(scale)||scale<.2||scale>1)return List.of();
        Box box=new Box(bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ);
        return build(new Point[]{socket},new Housing(box,box,null),consoleTurns,scale,false,true,tv,tvTurns,dx,dy,dz,seed);
    }

    /** Ordinary old TVs retain their exact socket/body geometry; no FC socket is substituted. */
    public static List<Quad> buildMultiOutLegacy(Point socket,Bounds bounds,int consoleTurns,double scale,
            boolean centered,boolean lcd,boolean wide,boolean large,boolean vintage,int tvTurns,double dx,double dy,double dz){
        if(socket==null||bounds==null||!Double.isFinite(scale)||scale<.2||scale>1||!Double.isFinite(dx+dy+dz)||dx*dx+dy*dy+dz*dz>64)return List.of();
        var b=new Box(bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ);
        var source=new Housing(b,b,null);
        var target=tvHousing(centered,lcd||wide||large,wide,large,vintage,tvTurns,dx,dy,dz);
        var sockets=HomeAvCableMesh.tvSockets(centered,lcd,wide,large,vintage,tvTurns,new Point(dx,dy,dz));
        var route=HomeAvCableLayout.routeMultiOut(socket,b,consoleTurns,sockets,target.outer(),tvTurns,dy);
        if(route.isEmpty())return List.of();
        var result=new ArrayList<Quad>();tube(result,route.subList(1,route.size()-1),CABLE_RADIUS,RUBBER);
        if(!clearOf(result,0,b)||!clearOf(result,0,target.outer()))return List.of();
        if(!multiOut(result,socket,outward(consoleTurns),scale,route.get(1),unit(subtract(route.get(2),route.get(1))),source,target.outer()))return List.of();
        if(!plugs(result,sockets,outward(tvTurns),route.get(route.size()-2),.65,
                unit(subtract(route.get(route.size()-3),route.get(route.size()-2))),target,b,false,CABLE_RADIUS))return List.of();
        if(result.size()>MAX_QUADS)return List.of();
        for(var q:result)for(var p:List.of(q.a(),q.b(),q.c(),q.d()))if(!Double.isFinite(p.x()+p.y()+p.z())||p.y()<Math.min(0,dy)-1e-9)return List.of();
        return List.copyOf(result);
    }

    public static Point[] tvSockets(ArcadeDisplayStyle style,int turns,Point offset) {
        Point[] sockets=new Point[3];
        for(int c=0;c<3;c++){var p=UserTvLayout.socket(style,turns,c);sockets[c]=add(new Point(p.x(),p.y(),p.z()),offset);}
        return sockets;
    }

    private static List<Quad> build(Point[] sockets,Housing console,int consoleTurns,double scale,boolean coax,boolean multi,
                                     ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz,long seed) {
        var curved=buildRoute(sockets,console,consoleTurns,scale,coax,multi,tv,tvTurns,dx,dy,dz,seed,true);
        return curved.isEmpty()?buildRoute(sockets,console,consoleTurns,scale,coax,multi,tv,tvTurns,dx,dy,dz,seed,false):curved;
    }
    private static List<Quad> buildRoute(Point[] sockets,Housing console,int consoleTurns,double scale,boolean coax,boolean multi,
                                     ArcadeDisplayStyle tv,int tvTurns,double dx,double dy,double dz,long seed,boolean curved) {
        if(!UserTvLayout.supports(tv))return List.of();
        boolean tight=coax&&UserTvLayout.wall(tv)&&Math.floorMod(consoleTurns-tvTurns,4)==0
                && tightWallGap(sockets[0],tvTurns,dx,dz);
        if(tight) {
            // The conservative block-sized box includes empty air behind the real
            // FC shell (rear at 14.66/16). Keep that air available to an elbow plug.
            Box real=orientedBox(new Box(0,0,0,1,.5,14.70/16),consoleTurns,new Point(0,0,0));
            console=new Housing(real,real,null);
        }
        var route=HomeAvCableLayout.routeUserTv(sockets,console.outer(),consoleTurns,tv,tvTurns,dx,dy,dz,
                tight?tightTail(sockets[0],outward(consoleTurns)):null,seed,curved);
        if(route.isEmpty())return List.of();
        var television=television(tv,tvTurns,new Point(dx,dy,dz));
        var result=new ArrayList<Quad>();
        tube(result,route.subList(1,route.size()-1),CABLE_RADIUS,RUBBER);
        if(!clearOf(result,0,console.outer())||!clearOf(result,0,television.outer()))return List.of();
        Point tangent=unit(subtract(route.get(2),route.get(1)));
        boolean sourceOk=tight?tightCoax(result,sockets[0],outward(consoleTurns),television.outer())
                :multi?multiOut(result,sockets[0],outward(consoleTurns),scale,route.get(1),tangent,console,television.outer())
                :plugs(result,sockets,outward(consoleTurns),route.get(1),scale,tangent,console,television.outer(),coax,CABLE_RADIUS);
        if(!sourceOk)return List.of();
        Point[] remote=tvSockets(tv,tvTurns,new Point(dx,dy,dz));
        Point junction=route.get(route.size()-2);
        if(UserTvLayout.wall(tv)) {
            if(!wallPlugs(result,remote,outward(tvTurns),junction,television,console.outer()))return List.of();
        } else if(!plugs(result,remote,outward(tvTurns),junction,UserTvLayout.panel(tv)?.8:.65,
                unit(subtract(route.get(route.size()-3),junction)),television,console.outer(),false,CABLE_RADIUS))return List.of();
        if(result.size()>MAX_QUADS)return List.of();
        for(Quad q:result)for(Point p:List.of(q.a(),q.b(),q.c(),q.d())) {
            if(!Double.isFinite(p.x()+p.y()+p.z())||p.y()<Math.min(0,dy)-1e-9)return List.of();
            if(UserTvLayout.wall(tv)&&!HomeAvCableLayout.beforeWall(p,tvTurns,dx,dz))return List.of();
        }
        return List.copyOf(result);
    }

    private static boolean tightWallGap(Point socket,int turns,double dx,double dz) {
        Point local=HomeHardwareRenderLayout.rotate(new Point(socket.x()-dx,socket.y(),socket.z()-dz),-turns);
        double gap=1-local.z();return gap>=.055&&gap<.38;
    }
    private static Point tightTail(Point socket,Point normal) {return add(add(socket,multiply(normal,.028)),new Point(0,-.06,0));}
    private static boolean tightCoax(List<Quad> out,Point socket,Point normal,Box other) {
        int first=out.size();Point barrel=add(socket,multiply(normal,.012));
        tube(out,List.of(socket,barrel),.026,METAL);
        var elbow=new ArrayList<Point>();
        for(int i=0;i<=12;i++){double a=i*Math.PI/24;elbow.add(add(add(barrel,multiply(normal,.016*Math.sin(a))),new Point(0,-.016*(1-Math.cos(a)),0)));}
        elbow.add(tightTail(socket,normal));tube(out,elbow,CABLE_RADIUS,RUBBER);
        return clearOf(out,first,other);
    }

    private static Housing television(ArcadeDisplayStyle style,int turns,Point offset) {
        var b=UserTvLayout.bounds(style,0);
        Box outer=orientedBox(new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16),turns,offset);
        if(!UserTvLayout.wall(style))return new Housing(outer,outer,null);
        // The short central bracket reaches the wall; it is not a solid full-width slab behind the AV sockets.
        Box body=orientedBox(new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,13.51/16),turns,offset);
        Box bracket=orientedBox(new Box(11.6/16,7.02/16,13.34/16,(UserTvLayout.width(style)*16-11.6)/16,8.68/16,1),turns,offset);
        return new Housing(outer,body,bracket);
    }

    private static boolean wallPlugs(List<Quad> result,Point[] sockets,Point normal,Point junction,Housing own,Box other) {
        int[] colors={YELLOW,WHITE,RED};
        for(int c=0;c<3;c++) {
            Point socket=sockets[c],tip=add(socket,multiply(normal,.015)),barrel=add(socket,multiply(normal,.04));
            int start=result.size();tube(result,List.of(socket,tip),.017,METAL);tube(result,List.of(tip,barrel),.027,colors[c]);
            var elbow=new ArrayList<Point>();
            for(int i=0;i<=8;i++){double a=i*Math.PI/16;elbow.add(add(add(barrel,multiply(normal,.035*Math.sin(a))),new Point(0,-.035*(1-Math.cos(a)),0)));}
            tube(result,elbow,.020,RUBBER);
            if(!clearOf(result,start,other))return false;
            Point tail=elbow.getLast();
            var path=curve(tail,add(tail,new Point(0,-.18,0)),add(junction,new Point(0,.08,0)),junction);
            int branch=result.size();tube(result,path,BRANCH_RADIUS,RUBBER);
            if(!clearOf(result,branch,own.body())||!clearOf(result,branch,own.base())||!clearOf(result,branch,other))return false;
        }
        return true;
    }

    private static boolean multiOut(List<Quad> result,Point socket,Point normal,double scale,Point junction,Point tangent,Housing own,Box other) {
        double size=scale/.45;
        Point barrel=add(socket,multiply(normal,.045*size)),tail=add(socket,multiply(normal,.072*size));
        Point side=multiply(new Point(normal.z(),0,-normal.x()),.9/32*size),up=new Point(0,.23/32*size,0);
        var corners=List.of(add(side,up),add(multiply(side,-1),up),subtract(multiply(side,-1),up),subtract(side,up));
        int first=result.size();
        for(int i=0;i<4;i++){Point a=add(socket,corners.get(i)),b=add(socket,corners.get((i+1)%4)),c=add(barrel,corners.get((i+1)%4)),d=add(barrel,corners.get(i));
            Point ab=subtract(b,a),ad=subtract(d,a);
            result.add(new Quad(a,b,c,d,unit(new Point(ab.y()*ad.z()-ab.z()*ad.y(),ab.z()*ad.x()-ab.x()*ad.z(),ab.x()*ad.y()-ab.y()*ad.x())),RUBBER));}
        result.add(new Quad(add(barrel,corners.get(3)),add(barrel,corners.get(2)),add(barrel,corners.get(1)),add(barrel,corners.get(0)),normal,RUBBER));
        tube(result,List.of(barrel,tail),CABLE_RADIUS,RUBBER);
        if(!clearOf(result,first,other))return false;
        Point shoulder=add(tail,multiply(normal,.22));
        int branch=result.size();tube(result,curve(tail,shoulder,subtract(junction,multiply(tangent,.08)),junction),CABLE_RADIUS,RUBBER);
        return clearOf(result,branch,own.body())&&clearOf(result,branch,other);
    }

    private static List<Point> curve(Point a,Point b,Point c,Point d) {
        var points=new ArrayList<Point>();
        for(int i=0;i<=32;i++){double t=i/32.0,u=1-t;points.add(add(add(multiply(a,u*u*u),multiply(b,3*u*u*t)),add(multiply(c,3*u*t*t),multiply(d,t*t*t))));}
        return points;
    }
}
