package cn.piq.sfchome.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure endpoint-based AV geometry. No worlds, chunks, FC model switches or mutable global state. */
public final class SfcAvCableGeometry {
    public static final double TRUNK_RADIUS=.010, BRANCH_RADIUS=.0045, TABLE_CLEARANCE=.022;
    public static final int SIDES=8, MAX_QUADS=6000;
    private static final double EPS=1e-8, GUARD=.16, REAR_EXIT=.28;
    private static final int RUBBER=0x292B2D, METAL=0xB6B8B6;
    private static final int[] COLORS={0xF2BF32,0xFFFFF6,0xC63831};
    private SfcAvCableGeometry() {}

    public record Vec(double x,double y,double z) {
        public Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}
        public Vec subtract(Vec v){return new Vec(x-v.x,y-v.y,z-v.z);}
        public Vec multiply(double k){return new Vec(x*k,y*k,z*k);}
        public double dot(Vec v){return x*v.x+y*v.y+z*v.z;}
        public Vec cross(Vec v){return new Vec(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x);}
        public double length(){return Math.sqrt(dot(this));}
        public Vec unit(){double n=length();return n<EPS?new Vec(0,0,0):multiply(1/n);}
        public boolean finite(){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z);}
    }
    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public boolean valid(){return Double.isFinite(minX)&&Double.isFinite(minY)&&Double.isFinite(minZ)&&Double.isFinite(maxX)&&Double.isFinite(maxY)&&Double.isFinite(maxZ)&&minX<maxX&&minY<maxY&&minZ<maxZ;}
        Box inset(double e){return new Box(minX+e,minY+e,minZ+e,maxX-e,maxY-e,maxZ-e);}
    }
    public record Endpoint(List<Vec> sockets,Box housing,Vec outward,double baseY,double plugScale) {
        public Endpoint { sockets=List.copyOf(sockets); }
    }
    public record Quad(Vec a,Vec b,Vec c,Vec d,Vec normal,int color,String part) {}
    public record Mesh(List<Quad> quads,List<Vec> trunk,double supportY,String rejection) {
        public Mesh {quads=List.copyOf(quads);trunk=List.copyOf(trunk);}
        public boolean visible(){return !quads.isEmpty();}
        static Mesh empty(String reason){return new Mesh(List.of(),List.of(),0,reason);}
    }
    private record Rect(double minX,double minZ,double maxX,double maxZ) {
        static Rect around(Box b){return new Rect(b.minX-GUARD,b.minZ-GUARD,b.maxX+GUARD,b.maxZ+GUARD);}
        boolean contains(Vec p){return p.x>minX+EPS&&p.x<maxX-EPS&&p.z>minZ+EPS&&p.z<maxZ-EPS;}
    }

    public static Vec rotate(Vec p,int turns) {
        return switch(Math.floorMod(turns,4)) {
            case 1 -> new Vec(1-p.z,p.y,p.x);
            case 2 -> new Vec(1-p.x,p.y,1-p.z);
            case 3 -> new Vec(p.z,p.y,1-p.x);
            default -> p;
        };
    }
    public static Vec outward(int turns){return rotate(new Vec(.5,0,1.5),turns).subtract(new Vec(.5,0,.5));}
    public static Endpoint console(int turns) {
        // User model has one MULTI OUT jack, not three RCA sockets.
        var socket=cn.piq.sfchome.layout.SfcConsoleScale.console(5.55,.695,15.5375);
        var sockets=List.of(rotate(new Vec(socket.x()/16,socket.y()/16,socket.z()/16),turns));
        var b=cn.piq.sfchome.layout.SfcConsoleScale.body(turns);
        Box box=new Box(b.minX()/16,0,b.minZ()/16,b.maxX()/16,7.62/16,b.maxZ()/16);
        return new Endpoint(sockets,box,outward(turns),0,.675);
    }

    private static boolean valid(Endpoint e) {
        if(e==null||(e.sockets.size()!=1&&e.sockets.size()!=3)||e.housing==null||e.outward==null||!e.housing.valid()||!e.outward.finite()||Math.abs(e.outward.y)>EPS||Math.abs(e.outward.length()-1)>EPS
                || !(Math.abs(e.outward.x)>1-EPS||Math.abs(e.outward.z)>1-EPS)||!Double.isFinite(e.baseY)||!Double.isFinite(e.plugScale)||e.plugScale<.2||e.plugScale>1)return false;
        for(Vec p:e.sockets) if(!p.finite()||p.y<e.baseY||Math.abs(p.x)+Math.abs(p.y)+Math.abs(p.z)>60)return false;
        for(int i=0;i<e.sockets.size();i++)for(int j=i+1;j<e.sockets.size();j++)if(e.sockets.get(i).subtract(e.sockets.get(j)).length()<.068*e.plugScale)return false;
        return true;
    }
    private static Vec center(Endpoint e){Vec sum=new Vec(0,0,0);for(Vec p:e.sockets)sum=sum.add(p);return sum.multiply(1.0/e.sockets.size());}
    private static Vec rear(Vec from,Endpoint e,double beyond,double y) {
        Vec n=e.outward;Box b=e.housing;
        if(n.x>.5)return new Vec(b.maxX+beyond,y,from.z);
        if(n.x<-.5)return new Vec(b.minX-beyond,y,from.z);
        if(n.z>.5)return new Vec(from.x,y,b.maxZ+beyond);
        return new Vec(from.x,y,b.minZ-beyond);
    }

    public static Mesh build(Endpoint first,Endpoint second) {
        if(!valid(first)||!valid(second)||center(first).subtract(center(second)).length()>12)return Mesh.empty("invalid-or-distant-endpoints");
        // Only equal base heights imply a shared tabletop. Unequal bases use a
        // conservative bridge above the higher base; arbitrary support blocks are not inferred.
        double support=Math.max(first.baseY,second.baseY)+TABLE_CLEARANCE;
        Vec start=rear(center(first),first,REAR_EXIT,support),end=rear(center(second),second,REAR_EXIT,support);
        if(start.subtract(end).length()<EPS)return Mesh.empty("degenerate-route");
        Rect a=Rect.around(first.housing),b=Rect.around(second.housing);
        if(a.contains(end)||b.contains(start))return Mesh.empty("rear-exit-blocked");
        List<Vec> path=shortestPath(start,end,a,b);
        if(path.isEmpty())return Mesh.empty("no-two-housing-route");
        List<Vec> trunk=rounded(path,a,b,support);
        if(trunk.size()<2)return Mesh.empty("degenerate-route");
        var result=new ArrayList<Quad>();tube(result,trunk,TRUNK_RADIUS,RUBBER,"trunk");
        if(!clear(result,0,first.housing)||!clear(result,0,second.housing))return Mesh.empty("trunk-triangle-housing-contact");
        Vec outgoing=trunk.get(1).subtract(trunk.get(0)).unit();
        Vec incoming=trunk.get(trunk.size()-2).subtract(trunk.getLast()).unit();
        if(!fanout(result,first,second.housing,start,outgoing,"console")||!fanout(result,second,first.housing,end,incoming,"tv"))return Mesh.empty("fanout-triangle-housing-contact");
        double floor=Math.min(first.baseY,second.baseY);
        for(Quad q:result)for(Vec p:List.of(q.a,q.b,q.c,q.d))if(p.y<floor-EPS)return Mesh.empty("below-support-base");
        if(result.size()>MAX_QUADS)return Mesh.empty("mesh-budget");
        return new Mesh(result,trunk,support,"");
    }

    private static List<Vec> shortestPath(Vec start,Vec end,Rect a,Rect b) {
        var nodes=new ArrayList<Vec>();nodes.add(start);nodes.add(end);
        for(Rect own:List.of(a,b))for(double x:new double[]{own.minX,own.maxX})for(double z:new double[]{own.minZ,own.maxZ}) {
            Vec p=new Vec(x,start.y,z);if(!a.contains(p)&&!b.contains(p))nodes.add(p);
        }
        double[] distance=new double[nodes.size()];Arrays.fill(distance,Double.POSITIVE_INFINITY);distance[0]=0;
        int[] prior=new int[nodes.size()];Arrays.fill(prior,-1);boolean[] seen=new boolean[nodes.size()];
        for(int count=0;count<nodes.size();count++) {
            int at=-1;for(int i=0;i<nodes.size();i++)if(!seen[i]&&(at<0||distance[i]<distance[at]))at=i;
            if(at<0||!Double.isFinite(distance[at]))return List.of();if(at==1)break;seen[at]=true;
            for(int j=0;j<nodes.size();j++)if(!seen[j]&&j!=at&&!intersects(nodes.get(at),nodes.get(j),a)&&!intersects(nodes.get(at),nodes.get(j),b)) {
                double d=distance[at]+nodes.get(at).subtract(nodes.get(j)).length();if(d<distance[j]-EPS){distance[j]=d;prior[j]=at;}
            }
        }
        if(!Double.isFinite(distance[1]))return List.of();var reverse=new ArrayList<Vec>();for(int i=1;i>=0;i=prior[i])reverse.add(nodes.get(i));return reverse.reversed();
    }
    private static boolean intersects(Vec a,Vec b,Rect r) {
        double t0=0,t1=1;double[] start={a.x,a.z},delta={b.x-a.x,b.z-a.z},low={r.minX+EPS,r.minZ+EPS},high={r.maxX-EPS,r.maxZ-EPS};
        for(int i=0;i<2;i++) {
            if(Math.abs(delta[i])<EPS){if(start[i]<low[i]||start[i]>high[i])return false;}
            else {double x=(low[i]-start[i])/delta[i],y=(high[i]-start[i])/delta[i];if(x>y){double old=x;x=y;y=old;}t0=Math.max(t0,x);t1=Math.min(t1,y);if(t0>t1)return false;}
        }
        return t0<=t1;
    }
    private static Vec blend(Vec a,Vec b,double t){return a.multiply(1-t).add(b.multiply(t));}
    private static List<Vec> rounded(List<Vec> path,Rect a,Rect b,double y) {
        var out=new ArrayList<Vec>();out.add(path.getFirst());
        // Rounded chords must stay outside slightly smaller guards. Original paths
        // travel the inflated boundary, leaving actual housing and pipe-radius clearance.
        Rect ga=new Rect(a.minX+.05,a.minZ+.05,a.maxX-.05,a.maxZ-.05),gb=new Rect(b.minX+.05,b.minZ+.05,b.maxX-.05,b.maxZ-.05);
        for(int i=1;i<path.size()-1;i++) {
            Vec left=path.get(i-1),corner=path.get(i),right=path.get(i+1);double before=corner.subtract(left).length(),after=corner.subtract(right).length();
            double trim=Math.min(.25,Math.min(before,after)*.25);boolean accepted=false;
            for(int attempt=0;attempt<5&&!accepted;attempt++,trim*=.5) {
                Vec enter=blend(corner,left,trim/before),leave=blend(corner,right,trim/after);var curve=new ArrayList<Vec>();curve.add(enter);
                for(int s=1;s<=8;s++){double t=s/8.0,u=1-t;curve.add(enter.multiply(u*u).add(corner.multiply(2*u*t)).add(leave.multiply(t*t)));}
                Vec last=out.getLast();boolean safe=true;for(Vec p:curve){if(intersects(last,p,ga)||intersects(last,p,gb)){safe=false;break;}last=p;}
                if(safe){out.addAll(curve);accepted=true;}
            }
            if(!accepted)out.add(corner);
        }
        out.add(path.getLast());return out;
    }

    private static boolean fanout(List<Quad> out,Endpoint own,Box other,Vec junction,Vec tangent,String name) {
        boolean multi=own.sockets.size()==1;
        for(int channel=0;channel<own.sockets.size();channel++) {
            Vec socket=own.sockets.get(channel),normal=own.outward;double scale=own.plugScale;
            Vec tip=socket.add(normal.multiply(.018*scale)),barrel=socket.add(normal.multiply(.10*scale)),tail=socket.add(normal.multiply(.145*scale));
            int plugStart=out.size();
            if(multi){
                double size=own.plugScale/.45;
                barrel=socket.add(normal.multiply(.045*size));tail=socket.add(normal.multiply(.072*size));
                multiPlug(out,socket,barrel,normal,size,name+"-multi-out");
                tube(out,List.of(barrel,tail),.009*size,RUBBER,name+"-strain-relief");
            }else{
                tube(out,List.of(socket,tip),.017*scale,METAL,name+"-plug-"+channel);
                tube(out,List.of(tip,barrel),.030*scale,COLORS[channel],name+"-plug-"+channel);
                tube(out,List.of(barrel,tail),.020*scale,RUBBER,name+"-plug-"+channel);
            }
            // The only allowed own-envelope crossing is this horizontal socket exit.
            // LCD feet extend beyond the rear panel, so a 2D outer envelope includes
            // empty space behind the actual port. The stub still clears the other device.
            Vec bend=rear(socket,own,.080,Math.max(socket.y,junction.y+.035));
            if(bend.subtract(tail).dot(normal)<0) bend=tail.add(normal.multiply(.015));
            tube(out,List.of(tail,new Vec(bend.x,socket.y,bend.z)),multi?TRUNK_RADIUS:BRANCH_RADIUS,RUBBER,name+"-axial-"+channel);
            if(!clear(out,plugStart,other))return false;
            Vec start=new Vec(bend.x,socket.y,bend.z),controlA=start.add(normal.multiply(.11)),controlB=junction.subtract(tangent.multiply(.06));
            var drop=new ArrayList<Vec>();
            for(int step=0;step<=24;step++) {
                double t=step/24.0,u=1-t;drop.add(start.multiply(u*u*u).add(controlA.multiply(3*u*u*t)).add(controlB.multiply(3*u*t*t)).add(junction.multiply(t*t*t)));
            }
            int curveStart=out.size();tube(out,drop,multi?TRUNK_RADIUS:BRANCH_RADIUS,RUBBER,name+"-drop-"+channel);
            if(!clear(out,curveStart,own.housing)||!clear(out,curveStart,other))return false;
        }
        return true;
    }

    private static void multiPlug(List<Quad> out,Vec start,Vec end,Vec normal,double size,String part){
        Vec side=new Vec(normal.z,0,-normal.x).multiply(.9/32*size),up=new Vec(0,.23/32*size,0);
        var corners=List.of(side.add(up),side.multiply(-1).add(up),side.multiply(-1).subtract(up),side.subtract(up));
        for(int i=0;i<4;i++){
            Vec a=start.add(corners.get(i)),b=start.add(corners.get((i+1)%4)),c=end.add(corners.get((i+1)%4)),d=end.add(corners.get(i));
            out.add(new Quad(a,b,c,d,b.subtract(a).cross(d.subtract(a)).unit(),RUBBER,part));
        }
        out.add(new Quad(end.add(corners.get(3)),end.add(corners.get(2)),end.add(corners.get(1)),end.add(corners.get(0)),normal,RUBBER,part));
    }

    private static void tube(List<Quad> out,List<Vec> path,double radius,int color,String part) {
        if(path.size()<2)return;var rings=new ArrayList<List<Vec>>();Vec oldU=null;
        for(int i=0;i<path.size();i++) {
            Vec tangent=path.get(Math.min(i+1,path.size()-1)).subtract(path.get(Math.max(0,i-1))).unit();
            if(tangent.length()<.5)return;
            Vec u=oldU==null?new Vec(0,1,0):oldU;u=u.subtract(tangent.multiply(u.dot(tangent))).unit();
            if(u.length()<.5)u=new Vec(1,0,0).subtract(tangent.multiply(tangent.x)).unit();
            Vec v=tangent.cross(u).unit();oldU=u;var ring=new ArrayList<Vec>();
            for(int side=0;side<SIDES;side++){double angle=2*Math.PI*side/SIDES;ring.add(path.get(i).add(u.multiply(radius*Math.cos(angle))).add(v.multiply(radius*Math.sin(angle))));}
            rings.add(ring);
        }
        for(int i=0;i<rings.size()-1;i++)for(int j=0;j<SIDES;j++) {
            Vec a=rings.get(i).get(j),b=rings.get(i).get((j+1)%SIDES),c=rings.get(i+1).get((j+1)%SIDES),d=rings.get(i+1).get(j);
            Vec normal=b.subtract(a).cross(d.subtract(a)).unit();if(normal.length()>.5)out.add(new Quad(a,b,c,d,normal,color,part));
        }
    }

    /** Exact triangle-box SAT on a slightly shrunken open housing interior. */
    public static boolean triangleIntersects(Vec aa,Vec bb,Vec cc,Box input) {
        Box b=input.inset(1e-7);if(!b.valid())return false;
        Vec center=new Vec((b.minX+b.maxX)/2,(b.minY+b.maxY)/2,(b.minZ+b.maxZ)/2),half=new Vec((b.maxX-b.minX)/2,(b.maxY-b.minY)/2,(b.maxZ-b.minZ)/2);
        Vec a=aa.subtract(center),c=cc.subtract(center),v=bb.subtract(center);Vec[] edges={v.subtract(a),c.subtract(v),a.subtract(c)};
        var axes=new ArrayList<Vec>(13);axes.add(new Vec(1,0,0));axes.add(new Vec(0,1,0));axes.add(new Vec(0,0,1));axes.add(edges[0].cross(edges[1]));
        for(Vec e:edges){axes.add(e.cross(new Vec(1,0,0)));axes.add(e.cross(new Vec(0,1,0)));axes.add(e.cross(new Vec(0,0,1)));}
        for(Vec axis:axes){if(axis.dot(axis)<EPS*EPS)continue;double p=a.dot(axis),q=v.dot(axis),r=c.dot(axis),extent=Math.abs(axis.x)*half.x+Math.abs(axis.y)*half.y+Math.abs(axis.z)*half.z;if(Math.min(p,Math.min(q,r))>extent||Math.max(p,Math.max(q,r))< -extent)return false;}
        return true;
    }
    private static boolean clear(List<Quad> quads,int from,Box box) {
        for(int i=from;i<quads.size();i++){Quad q=quads.get(i);if(triangleIntersects(q.a,q.b,q.c,box)||triangleIntersects(q.a,q.c,q.d,box))return false;}return true;
    }
}
