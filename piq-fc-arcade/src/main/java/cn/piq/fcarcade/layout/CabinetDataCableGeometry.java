package cn.piq.fcarcade.layout;

import java.util.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Box;

/** Bounded display-only cable. Visibility graph routes around both cabinet footprints, never through their bodies. */
public final class CabinetDataCableGeometry {
    public static final int SIDES=8, MAX_QUADS=512;
    public static final double RADIUS=.015, VIEW_RANGE=48, CLEARANCE=.08;
    // Centre line just above the cabinet support plane: leave 2 mm under the tube.
    public static final double FLOOR_HEIGHT=RADIUS+.002;
    public record Quad(Point a,Point b,Point c,Point d,Point normal,int color) {}
    private CabinetDataCableGeometry() {}
    public static Point socket(boolean dual,int turns) {
        return socket(dual,turns,false);
    }
    public static Point socket(boolean dual,int turns,boolean compact) {
        return socket(dual,turns,compact,false);
    }
    private static Point socket(boolean dual,int turns,boolean compact,boolean portrait) {
        Box box=body(dual,0,compact,portrait);return RocketArcadeGeometry.rotate(new Point(dual?1:.5,FLOOR_HEIGHT,box.maxZ()+.012),turns);
    }
    public static Box body(boolean dual,int turns) {return body(dual,turns,false);}
    public static Box body(boolean dual,int turns,boolean compact) {return dual?DualCabinetGeometry.bounds(turns,compact):RocketArcadeGeometry.selectionBox(turns);}
    private static Box body(boolean dual,int turns,boolean compact,boolean portrait) {return portrait?PortraitCabinetGeometry.bounds(turns):body(dual,turns,compact);}
    public static List<Point> path(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz) {
        return path(firstDual,firstTurns,secondDual,secondTurns,dx,dy,dz,false,false);
    }
    public static List<Point> path(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz,boolean firstCompact,boolean secondCompact) {
        return path(firstDual,firstTurns,secondDual,secondTurns,dx,dy,dz,firstCompact,secondCompact,false,false);
    }
    public static List<Point> path(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz,boolean firstCompact,boolean secondCompact,boolean firstPortrait,boolean secondPortrait) {
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!Double.isFinite(dz)||dx*dx+dy*dy+dz*dz>256||dx==0&&dy==0&&dz==0)return List.of();
        // Always leave the rear plinth, even when cabinets stand side by side.
        // Adjacent rear panels can leave less than the normal lead length. Keep a
        // bounded fallback, still outside the bodies plus more than the wire radius.
        for(double[] setup:new double[][]{{CLEARANCE,.20},{.02,.10},{.02,.035}}){
            var result=route(firstTurns,secondTurns,dx,dy,dz,socket(firstDual,firstTurns,firstCompact,firstPortrait),socket(secondDual,secondTurns,secondCompact,secondPortrait),body(firstDual,firstTurns,firstCompact,firstPortrait),body(secondDual,secondTurns,secondCompact,secondPortrait),setup[0],setup[1]);
            if(!result.isEmpty())return result;
        }
        return List.of();
    }
    private static List<Point> route(int firstTurns,int secondTurns,double dx,double dy,double dz,Point a,Point otherSocket,Box firstBody,Box secondBody,double clearance,double lead) {
        Point offset=new Point(dx,dy,dz),b=add(otherSocket,offset);
        Point aa=add(a,scale(outward(firstTurns),lead)),bb=add(b,scale(outward(secondTurns),lead));
        double floor=Math.min(0,dy)+FLOOR_HEIGHT;
        var boxes=List.of(expand(firstBody,new Point(0,0,0),clearance),expand(secondBody,offset,clearance));
        Point af=new Point(aa.x(),floor,aa.z()),bf=new Point(bb.x(),floor,bb.z());
        var nodes=new ArrayList<Point>();nodes.add(af);nodes.add(bf);
        for(var box:boxes)for(double x:new double[]{box.minX(),box.maxX()})for(double z:new double[]{box.minZ(),box.maxZ()}){
            var p=new Point(x,floor,z);if(boxes.stream().noneMatch(o->inside(p,o)))nodes.add(p);
        }
        if(boxes.stream().anyMatch(box->inside(af,box)||inside(bf,box)))return List.of();
        double[] distance=new double[nodes.size()];int[] before=new int[nodes.size()];boolean[] done=new boolean[nodes.size()];
        Arrays.fill(distance,Double.POSITIVE_INFINITY);Arrays.fill(before,-1);distance[0]=0;
        for(int count=0;count<nodes.size();count++){
            int u=-1;for(int i=0;i<nodes.size();i++)if(!done[i]&&(u<0||distance[i]<distance[u]))u=i;
            if(u<0||!Double.isFinite(distance[u]))break;done[u]=true;if(u==1)break;
            for(int v=0;v<nodes.size();v++)if(!done[v]&&clear(nodes.get(u),nodes.get(v),boxes)){
                double value=distance[u]+length(subtract(nodes.get(u),nodes.get(v)));if(value<distance[v]){distance[v]=value;before[v]=u;}
            }
        }
        if(!Double.isFinite(distance[1]))return List.of();
        var middle=new ArrayList<Point>();for(int at=1;at!=-1;at=before[at])middle.add(nodes.get(at));Collections.reverse(middle);
        var result=new ArrayList<Point>();result.add(a);result.add(aa);result.addAll(middle);result.add(bb);result.add(b);
        for(int i=result.size()-1;i>0;i--)if(length(subtract(result.get(i),result.get(i-1)))<1e-7)result.remove(i);
        return List.copyOf(result);
    }
    public static List<Quad> build(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz) {
        return build(firstDual,firstTurns,secondDual,secondTurns,dx,dy,dz,false,false);
    }
    public static List<Quad> build(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz,boolean firstCompact,boolean secondCompact) {
        return build(firstDual,firstTurns,secondDual,secondTurns,dx,dy,dz,firstCompact,secondCompact,false,false);
    }
    public static List<Quad> build(boolean firstDual,int firstTurns,boolean secondDual,int secondTurns,double dx,double dy,double dz,boolean firstCompact,boolean secondCompact,boolean firstPortrait,boolean secondPortrait) {
        var path=path(firstDual,firstTurns,secondDual,secondTurns,dx,dy,dz,firstCompact,secondCompact,firstPortrait,secondPortrait);if(path.isEmpty())return List.of();
        var result=new ArrayList<Quad>();
        cable(result,path,RADIUS,0x808080);
        // Connections are concealed under the cabinet plinth; no oversized floating plug mesh.
        if(result.size()>MAX_QUADS)throw new IllegalArgumentException("Cabinet cable mesh exceeds budget");return List.copyOf(result);
    }
    /** Share one ring at each bend, rather than leaving cracks between independent cylinders. */
    private static void cable(List<Quad> out,List<Point> path,double radius,int color) {
        Point[][] rings=new Point[path.size()][SIDES],normals=new Point[path.size()][SIDES];
        Point previousU=null;
        for(int i=0;i<path.size();i++){
            Point p=path.get(i);
            Point incoming=i==0?unit(subtract(path.get(1),p)):unit(subtract(p,path.get(i-1)));
            Point outgoing=i==path.size()-1?incoming:unit(subtract(path.get(i+1),p));
            Point sum=add(incoming,outgoing),axis=length(sum)>1e-8?unit(sum):incoming;
            Point u=previousU==null?new Point(0,0,0):subtract(previousU,scale(axis,dot(previousU,axis)));
            if(length(u)<1e-8)u=cross(axis,Math.abs(axis.y())>.95?new Point(1,0,0):new Point(0,1,0));
            u=unit(u);Point v=cross(axis,u);previousU=u;
            for(int side=0;side<SIDES;side++){
                double angle=side*Math.PI*2/SIDES;
                normals[i][side]=add(scale(u,Math.cos(angle)),scale(v,Math.sin(angle)));
                rings[i][side]=add(p,scale(normals[i][side],radius));
            }
        }
        for(int i=1;i<path.size();i++)for(int side=0;side<SIDES;side++){
            int next=(side+1)%SIDES;
            Point normal=unit(add(add(normals[i-1][side],normals[i-1][next]),add(normals[i][side],normals[i][next])));
            out.add(new Quad(rings[i-1][side],rings[i-1][next],rings[i][next],rings[i][side],normal,color));
        }
    }
    private static void tube(List<Quad> out,Point a,Point b,double radius,int color) {
        Point axis=unit(subtract(b,a)),u=unit(cross(axis,Math.abs(axis.y())>.95?new Point(1,0,0):new Point(0,1,0))),v=cross(axis,u);
        for(int side=0;side<SIDES;side++){
            double t=side*Math.PI*2/SIDES,s=(side+1)*Math.PI*2/SIDES;Point n=add(scale(u,Math.cos(t)),scale(v,Math.sin(t))),m=add(scale(u,Math.cos(s)),scale(v,Math.sin(s)));
            out.add(new Quad(add(a,scale(n,radius)),add(a,scale(m,radius)),add(b,scale(m,radius)),add(b,scale(n,radius)),unit(add(n,m)),color));
        }
    }
    private static boolean clear(Point a,Point b,List<Box> boxes) {for(Box box:boxes)if(intersects(a,b,box))return false;return true;}
    private static boolean inside(Point p,Box b) {return p.x()>b.minX()+1e-8&&p.x()<b.maxX()-1e-8&&p.z()>b.minZ()+1e-8&&p.z()<b.maxZ()-1e-8;}
    private static boolean intersects(Point a,Point b,Box box) {
        double low=0,high=1;double[] start={a.x(),a.z()},delta={b.x()-a.x(),b.z()-a.z()},min={box.minX()+1e-8,box.minZ()+1e-8},max={box.maxX()-1e-8,box.maxZ()-1e-8};
        for(int axis=0;axis<2;axis++){if(Math.abs(delta[axis])<1e-12){if(start[axis]<=min[axis]||start[axis]>=max[axis])return false;}
            else{double t=(min[axis]-start[axis])/delta[axis],s=(max[axis]-start[axis])/delta[axis];low=Math.max(low,Math.min(t,s));high=Math.min(high,Math.max(t,s));if(low>=high)return false;}}
        return low<high&&high>0&&low<1;
    }
    private static Box expand(Box box,Point p,double clearance) {return new Box(box.minX()+p.x()-clearance,box.minY()+p.y(),box.minZ()+p.z()-clearance,box.maxX()+p.x()+clearance,box.maxY()+p.y(),box.maxZ()+p.z()+clearance);}
    private static Point outward(int turns) {Point p=RocketArcadeGeometry.rotate(new Point(.5,0,1.5),turns);return new Point(p.x()-.5,0,p.z()-.5);}
    private static Point add(Point a,Point b) {return new Point(a.x()+b.x(),a.y()+b.y(),a.z()+b.z());}
    private static Point subtract(Point a,Point b) {return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    private static Point scale(Point p,double s) {return new Point(p.x()*s,p.y()*s,p.z()*s);}
    private static double length(Point p) {return Math.sqrt(p.x()*p.x()+p.y()*p.y()+p.z()*p.z());}
    private static double dot(Point a,Point b) {return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    private static Point unit(Point p) {return scale(p,1/length(p));}
    private static Point cross(Point a,Point b) {return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
}
