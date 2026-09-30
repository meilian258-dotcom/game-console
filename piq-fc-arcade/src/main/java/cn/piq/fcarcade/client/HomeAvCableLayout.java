package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeConsoleLayout;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure, bounded route around the two known housings. No world access or renderer state. */
public final class HomeAvCableLayout {
    public static final int MAX_ROUTE_POINTS = 256;
    // Centerline height above the common device base: trunk radius .023 + .004 gap.
    // The previous minimum(socketY) floor kept a long cable suspended above the table.
    public static final double TABLE_CENTERLINE_CLEARANCE = .027;
    // Reserve room for a real pipe fillet, rather than turning around the exact
    // housing corner and trying to smooth through its solid wall afterwards.
    private static final double CLEARANCE = .16, EXIT = .30, STEP = .05, EPSILON = 1e-9;
    public static final double CORNER_TRIM = .34;
    private HomeAvCableLayout() {}

    private record Rect(double minX, double minZ, double maxX, double maxZ) {
        Rect expanded() { return new Rect(minX - CLEARANCE, minZ - CLEARANCE, maxX + CLEARANCE, maxZ + CLEARANCE); }
        Rect filletGuard() { return new Rect(minX+.12,minZ+.12,maxX-.12,maxZ-.12); }
        boolean contains(Point p) {
            return p.x() > minX + EPSILON && p.x() < maxX - EPSILON
                    && p.z() > minZ + EPSILON && p.z() < maxZ - EPSILON;
        }
    }

    /**
     * Already-sampled console-local block coordinates, including exact socket endpoints.
     * Cache by hardware coordinates/states; connect consecutive points directly, without
     * another sag interpolation. Empty means the conservative two-housing route is blocked.
     */
    public static List<Point> route(boolean subor, int consoleTurns, boolean centeredTv, int tvTurns,
                                    double dx, double dy, double dz) {
        return route(subor, false, consoleTurns, centeredTv, tvTurns, dx, dy, dz);
    }

    public static List<Point> route(boolean subor, boolean wide, int consoleTurns, boolean centeredTv, int tvTurns,
                                    double dx, double dy, double dz) {
        return route(subor, wide, consoleTurns, centeredTv, false, tvTurns, dx, dy, dz);
    }

    public static List<Point> route(boolean subor, boolean wide, int consoleTurns, boolean centeredTv, boolean lcd, int tvTurns,
                                    double dx, double dy, double dz) {
        return route(subor,wide,consoleTurns,centeredTv,lcd,false,tvTurns,dx,dy,dz);
    }

    /** Wide LCD is a separate endpoint geometry; the old overload keeps exact legacy routes. */
    public static List<Point> route(boolean subor, boolean wide, int consoleTurns, boolean centeredTv,
                                    boolean lcd, boolean wideLcd, int tvTurns, double dx,double dy,double dz) {
        return route(subor,wide,consoleTurns,centeredTv,lcd,wideLcd,false,tvTurns,dx,dy,dz);
    }

    public static List<Point> route(boolean subor,boolean wide,int consoleTurns,boolean centeredTv,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,int tvTurns,double dx,double dy,double dz) {
        return route(subor,wide,consoleTurns,centeredTv,lcd,wideLcd,largeLcd,false,tvTurns,dx,dy,dz);
    }

    public static List<Point> route(boolean subor,boolean wide,int consoleTurns,boolean centeredTv,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,boolean vintageTv,int tvTurns,double dx,double dy,double dz) {
        return route(subor,wide,consoleTurns,centeredTv,lcd,wideLcd,largeLcd,vintageTv,tvTurns,dx,dy,dz,false);
    }

    public static List<Point> route(boolean subor,boolean wide,int consoleTurns,boolean centeredTv,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,boolean vintageTv,int tvTurns,double dx,double dy,double dz,boolean compact) {
        if(vintageTv){lcd=false;wideLcd=false;largeLcd=false;centeredTv=false;}
        lcd |= wideLcd || largeLcd;
        if(largeLcd)wideLcd=false;
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || dx * dx + dy * dy + dz * dz > 64 + EPSILON) return List.of();
        Point start = HomeHardwareRenderLayout.rotate(subor
                ? new Point(wide ? HomeConsoleLayout.WIDE_AV_X : HomeConsoleLayout.AV_X,
                    wide ? HomeConsoleLayout.WIDE_AV_Y : HomeConsoleLayout.AV_Y,
                    HomeConsoleLayout.suborZ(wide ? HomeConsoleLayout.WIDE_AV_Z : HomeConsoleLayout.AV_Z,wide&&compact))
                : HomeHardwareRenderLayout.CONSOLE_CABLE, consoleTurns);
        var vintageSocket= vintageTv ? cn.piq.fcarcade.home.VintageTvLayout.socket(tvTurns,0) : null;
        Point remote = vintageTv ? new Point(vintageSocket.x(),vintageSocket.y(),vintageSocket.z())
                : lcd ? HomeHardwareRenderLayout.rotate(new Point((wideLcd?9.0:5.0)/16,4.0/16,8.23/16),tvTurns)
                : HomeHardwareRenderLayout.tvCable(tvTurns, centeredTv);
        Point end = new Point(dx + remote.x(), dy + remote.y(), dz + remote.z());
        Rect console = consoleRect(subor, wide, compact, consoleTurns).expanded();
        Rect tv = tvRect(centeredTv, lcd, wideLcd, largeLcd, vintageTv, tvTurns, dx, dz).expanded();
        boolean sameBase = Math.abs(dy) < EPSILON;
        // A shared tabletop is only inferred for equal device base heights. For
        // stepped platforms keep the previous suspended bridge: no imaginary
        // lower table may pull the whole wire through an unknown upper platform.
        double support = sameBase ? TABLE_CENTERLINE_CLEARANCE : Math.min(start.y(),end.y());
        // One trunk starts behind the middle of the three RCA heads, not behind
        // yellow alone; the exact yellow endpoints remain for interaction identity.
        double consoleShift = subor ? (wide ? -HomeConsoleLayout.WIDE_AV_SPACING : (12.77+12.14+10.25)/48-HomeConsoleLayout.AV_X) : 0;
        Point from = exit(lateral(start, consoleShift, consoleTurns), console, consoleTurns);
        Point to = exit(lateral(end, vintageTv ? -2.0/16 : lcd ? 3.0/16 : 2.86/16, tvTurns), tv, tvTurns);
        if (sameBase) { from=lowerJunction(from,support); to=lowerJunction(to,support); }
        // Only the socket-to-own-rear stub may enter its own housing. It may
        // never pass through the other housing, even if the models overlap.
        if (intersects(start, from, tv) || intersects(end, to, console)
                || console.contains(to) || tv.contains(from)) return List.of();

        List<Point> nodes = new ArrayList<>();
        nodes.add(from); nodes.add(to);
        addCorners(nodes, console, tv); addCorners(nodes, tv, console);
        List<Point> path = shortestPath(nodes, console, tv);
        if (path.isEmpty()) return List.of();
        return sample(start, end, roundCorners(path,console.filletGuard(),tv.filletGuard()), support, sameBase);
    }

    /** Custom rear connector, legacy TV; reuses the same housing avoidance and corner sampler. */
    static List<Point> routeMultiOut(Point start,HomeAvCableMesh.Box source,int sourceTurns,
                                    Point[] remote,HomeAvCableMesh.Box target,int targetTurns,double dy){
        if(remote.length!=3)return List.of();
        Rect console=new Rect(source.minX(),source.minZ(),source.maxX(),source.maxZ()).expanded();
        Rect tv=new Rect(target.minX(),target.minZ(),target.maxX(),target.maxZ()).expanded();
        double support=Math.min(0,dy)+USER_TV_SUPPORT;
        Point from=lowerJunction(exit(start,console,sourceTurns),support);
        Point to=lowerJunction(exit(remote[1],tv,targetTurns),support);
        if(intersects(start,from,tv)||intersects(remote[1],to,console)||console.contains(to)||tv.contains(from))return List.of();
        var nodes=new ArrayList<Point>();nodes.add(from);nodes.add(to);
        addCorners(nodes,console,tv);addCorners(nodes,tv,console);
        var path=shortestPath(nodes,console,tv);if(path.isEmpty())return List.of();
        return sample(start,remote[0],roundCorners(path,console.filletGuard(),tv.filletGuard()),support,true);
    }

    private static Point lateral(Point point, double amount, int turns) {
        return switch(Math.floorMod(turns,4)) {
            case 1 -> new Point(point.x(),point.y(),point.z()+amount);
            case 2 -> new Point(point.x()-amount,point.y(),point.z());
            case 3 -> new Point(point.x(),point.y(),point.z()-amount);
            default -> new Point(point.x()+amount,point.y(),point.z());
        };
    }

    /** Cache-build-only fillets. Each accepted chord clears both conservative
     * boxes; HomeAvCableMesh separately SAT-tests every resulting pipe triangle. */
    private static List<Point> roundCorners(List<Point> path, Rect first, Rect second) {
        return roundCorners(path,first,second,List.of());
    }
    private static List<Point> roundCorners(List<Point> path, Rect first, Rect second,List<Rect> leadGuards) {
        if(path.size()<3)return path;
        var result=new ArrayList<Point>();result.add(path.getFirst());
        for(int i=1;i<path.size()-1;i++) {
            Point a=path.get(i-1),b=path.get(i),c=path.get(i+1);
            double incoming=distance(a,b),outgoing=distance(b,c);
            if(incoming<EPSILON||outgoing<EPSILON){result.add(b);continue;}
            double trim=Math.min(CORNER_TRIM,Math.min(incoming,outgoing)*.28);
            boolean rounded=false;
            for(int attempt=0;attempt<5&&trim>.003;attempt++,trim*=.5) {
                Point entry=blend(b,a,trim/incoming),leave=blend(b,c,trim/outgoing);
                var curve=new ArrayList<Point>();curve.add(entry);
                for(int step=1;step<=12;step++) {
                    double t=step/12.0,u=1-t;
                    curve.add(new Point(u*u*entry.x()+2*u*t*b.x()+t*t*leave.x(),0,
                            u*u*entry.z()+2*u*t*b.z()+t*t*leave.z()));
                }
                boolean clear=true;Point prior=result.getLast();
                for(Point point:curve) {
                    if(intersects(prior,point,first)||intersects(prior,point,second)){clear=false;break;}
                    for(Rect lead:leadGuards)if(intersects(prior,point,lead)){clear=false;break;}
                    if(!clear)break;
                    prior=point;
                }
                if(clear){result.addAll(curve);rounded=true;break;}
            }
            if(!rounded)result.add(b);
        }
        result.add(path.getLast());return result;
    }
    private static Point blend(Point a,Point b,double t) {
        return new Point(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t);
    }

    private static Rect consoleRect(boolean subor, boolean wide, boolean compact, int turns) {
        if (!subor) return new Rect(0, 0, 1, 1);
        var bounds = HomeConsoleLayout.suborBounds(turns, wide,compact);
        return new Rect(bounds.minX() / 16, bounds.minZ() / 16, bounds.maxX() / 16, bounds.maxZ() / 16);
    }

    /** New user TVs: wall leads drop below the shell before the trunk travels in front of the wall. */
    static List<Point> routeUserTv(Point[] sockets, HomeAvCableMesh.Box consoleBounds,int consoleTurns,
                                  ArcadeDisplayStyle style,int tvTurns,double dx,double dy,double dz) {
        return routeUserTv(sockets,consoleBounds,consoleTurns,style,tvTurns,dx,dy,dz,null);
    }

    /** A short connector may supply its flexible tail; gravity/routing remains shared by every console. */
    static List<Point> routeUserTv(Point[] sockets, HomeAvCableMesh.Box consoleBounds,int consoleTurns,
                                  ArcadeDisplayStyle style,int tvTurns,double dx,double dy,double dz,Point closeTail) {
        return routeUserTv(sockets,consoleBounds,consoleTurns,style,tvTurns,dx,dy,dz,closeTail,
                routeSeed(consoleTurns,tvTurns,dx,dy,dz),true);
    }
    static List<Point> routeUserTv(Point[] sockets, HomeAvCableMesh.Box consoleBounds,int consoleTurns,
                                  ArcadeDisplayStyle style,int tvTurns,double dx,double dy,double dz,Point closeTail,
                                  long seed,boolean curved) {
        if(!UserTvLayout.supports(style)||!Double.isFinite(dx+dy+dz)||dx*dx+dy*dy+dz*dz>64+EPSILON)return List.of();
        Point start=sockets[0],center=new Point(0,0,0);
        for(Point p:sockets)center=new Point(center.x()+p.x()/sockets.length,center.y()+p.y()/sockets.length,center.z()+p.z()/sockets.length);
        var socket=UserTvLayout.socket(style,tvTurns,0);
        Point end=new Point(dx+socket.x(),dy+socket.y(),dz+socket.z());
        var bounds=UserTvLayout.bounds(style,tvTurns);
        Rect raw=new Rect(consoleBounds.minX(),consoleBounds.minZ(),consoleBounds.maxX(),consoleBounds.maxZ());
        Rect console=closeTail==null?raw.expanded():new Rect(raw.minX-.03,raw.minZ-.03,raw.maxX+.03,raw.maxZ+.03);
        boolean wall=UserTvLayout.wall(style);
        // A wall panel has no foot: the routed trunk is below its bottom, never behind the wall.
        Rect tv=wall?new Rect(100,100,100,100):new Rect(dx+bounds.minX()/16,dz+bounds.minZ()/16,dx+bounds.maxX()/16,dz+bounds.maxZ()/16).expanded();
        double support=Math.min(0,dy)+USER_TV_SUPPORT;
        Point origin=closeTail==null?exit(center,console,consoleTurns):closeTail;
        Point from=new Point(origin.x(),support,origin.z()),to;
        if(wall) {
            var mid=UserTvLayout.socket(style,0,1);
            var rear=HomeHardwareRenderLayout.rotate(new Point(mid.x(),0,.955),tvTurns);
            // Split only beside the television, never all the way to the floor.
            to=new Point(dx+rear.x(),Math.max(support,dy+UserTvLayout.bounds(style,0).minY()/16-.10),dz+rear.z());
        } else {
            var mid=UserTvLayout.socket(style,tvTurns,1);
            Point rear=exit(new Point(dx+mid.x(),dy+mid.y(),dz+mid.z()),tv,tvTurns);
            to=new Point(rear.x(),support,rear.z());
        }
        // A TV plug may overlap the console in plan view but be entirely above it.
        // Final mesh validation still checks every triangle against real housings.
        boolean remoteAtConsoleHeight=Math.min(end.y(),to.y())<=consoleBounds.maxY()+.03
                && Math.max(end.y(),to.y())>=consoleBounds.minY()-.03;
        if(intersects(start,from,tv)||(remoteAtConsoleHeight&&intersects(end,to,console))
                ||console.contains(to)||tv.contains(from))return List.of();
        // The horizontal run belongs on the common support plane. Only the TV-side
        // column rises: never lift the cable at the console and bridge through the air.
        Point foot=new Point(to.x(),support,to.z());
        // The trunk may leave a flexible junction sideways or continue away
        // from its socket, never turn back underneath its own molded plug.
        // Reserve both connector corridors as well as the housings: checking
        // only the first edge would permit a later edge to cross the same lead.
        Point firstDirection=closeTail==null?HomeAvCableMesh.outward(consoleTurns):null;
        Point lastDirection=wall?null:HomeAvCableMesh.outward(tvTurns);
        var leadGuards=new ArrayList<Rect>();
        if(firstDirection!=null)leadGuards.add(connectorCorridor(center,from,firstDirection));
        if(lastDirection!=null) {
            var mid=UserTvLayout.socket(style,tvTurns,1);
            leadGuards.add(connectorCorridor(new Point(dx+mid.x(),dy+mid.y(),dz+mid.z()),foot,lastDirection));
        }
        // Orthogonal visibility grid: no long diagonal chord across a tabletop.
        // Include the two housing borders so each 90-degree turn can clear the
        // actual mesh, then round that turn within its reserved clearance.
        var nodes=orthogonalNodes(from,foot,console,tv,!wall,leadGuards);
        if(wall) {
            if(!beforeWall(from,tvTurns,dx,dz)||!beforeWall(to,tvTurns,dx,dz))return List.of();
            nodes.removeIf(p->!beforeWall(p,tvTurns,dx,dz));
        }
        var path=shortestPath(nodes,console,tv,true,firstDirection,lastDirection,leadGuards);if(path.isEmpty())return List.of();
        if(wall) {
            var anchors=new ArrayList<Point>();
            if(closeTail!=null)anchors.add(closeTail);
            for(Point p:path)anchors.add(new Point(p.x(),support,p.z()));
            if(length3(anchors.getLast(),to)>EPSILON)anchors.add(to);
            var result=new ArrayList<Point>();result.add(start);result.addAll(roundSpatial(anchors));result.add(end);
            return result.size()<=MAX_ROUTE_POINTS?gentleBends(List.copyOf(result),seed,curved,console.filletGuard(),tv.filletGuard(),leadGuards):List.of();
        }
        var result=sample(start,end,roundCorners(path,console.filletGuard(),tv.filletGuard(),leadGuards),support,true);
        return gentleBends(result,seed,curved,console.filletGuard(),tv.filletGuard(),leadGuards);
    }

    /** All new-TV trunks use radius .015 plus .004 air gap. */
    static final double USER_TV_SUPPORT=.019;

    /** Stable fallback for callers that do not have a connection UUID. No global or frame RNG. */
    static long routeSeed(int sourceTurns,int tvTurns,double dx,double dy,double dz) {
        return mix(Double.doubleToLongBits(dx)^Long.rotateLeft(Double.doubleToLongBits(dy),17)
                ^Long.rotateLeft(Double.doubleToLongBits(dz),37)^Math.floorMod(sourceTurns,4)*31L^Math.floorMod(tvTurns,4));
    }
    private static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }

    /** Small planar bows only on long supported runs. End necks and corners keep
     * their exact tangent, height and clearance. An unsafe variant falls back to
     * the already validated regular route rather than changing connector physics. */
    private static List<Point> gentleBends(List<Point> base,long seed,boolean enabled,Rect first,Rect second,List<Rect> leads) {
        if(!enabled||base.size()<5)return base;
        var result=new ArrayList<>(base);
        for(int start=1;start<base.size()-2;) {
            Point a=base.get(start),next=base.get(start+1);double dx=next.x()-a.x(),dz=next.z()-a.z(),span=Math.hypot(dx,dz);
            if(span<EPSILON||Math.abs(next.y()-a.y())>EPSILON){start++;continue;}
            double ux=dx/span,uz=dz/span;int end=start+1;
            while(end+1<base.size()-1) {
                Point p=base.get(end+1);double x=p.x()-a.x(),z=p.z()-a.z();
                if(Math.abs(p.y()-a.y())>EPSILON||Math.abs(x*uz-z*ux)>1e-7
                        ||x*ux+z*uz<span-EPSILON)break;
                end++;span=x*ux+z*uz;
            }
            if(span>.85) {
                long style=mix(seed+0x9e3779b97f4a7c15L*start);
                double amplitude=Math.min(.12,span*.055)*(.55+((style>>>11)&1023)/1023.0*.45);
                if((style&1)!=0)amplitude=-amplitude;
                double neck=Math.min(.20,span*.2),usable=span-2*neck;
                for(int i=start+1;i<end;i++) {
                    Point p=base.get(i);double along=(p.x()-a.x())*ux+(p.z()-a.z())*uz;
                    double t=Math.max(0,Math.min(1,(along-neck)/usable));
                    double bow=amplitude*16*t*t*(1-t)*(1-t);
                    result.set(i,new Point(p.x()-uz*bow,p.y(),p.z()+ux*bow));
                }
            }
            start=end;
        }
        for(int i=2;i<result.size()-1;i++) {
            Point a=result.get(i-1),b=result.get(i);
            if(intersects(a,b,first)||intersects(a,b,second))return base;
            for(Rect lead:leads)if(intersects(a,b,lead))return base;
        }
        if(crossesItself(result))return base;
        return List.copyOf(result);
    }

    private static boolean crossesItself(List<Point> path) {
        for(int i=2;i<path.size()-1;i++)for(int j=i+2;j<path.size()-1;j++) {
            Point a=path.get(i-1),b=path.get(i),c=path.get(j-1),d=path.get(j);
            if(Math.max(Math.min(a.y(),b.y()),Math.min(c.y(),d.y()))
                    >Math.min(Math.max(a.y(),b.y()),Math.max(c.y(),d.y()))+.02)continue;
            double abC=cross(a,b,c),abD=cross(a,b,d),cdA=cross(c,d,a),cdB=cross(c,d,b);
            if(abC*abD<-EPSILON&&cdA*cdB<-EPSILON)return true;
        }
        return false;
    }
    private static double cross(Point a,Point b,Point c) {return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());}

    private static Rect connectorCorridor(Point socket,Point junction,Point normal) {
        // The transverse clearance covers three RCA strands as they merge.
        // Keep the junction itself on the open boundary so a lateral departure
        // is legal; no arbitrary ray or per-TV route exception is introduced.
        double halfWidth=.10;
        return Math.abs(normal.x())>.5
                ?new Rect(Math.min(socket.x(),junction.x()),junction.z()-halfWidth,
                    Math.max(socket.x(),junction.x()),junction.z()+halfWidth)
                :new Rect(junction.x()-halfWidth,Math.min(socket.z(),junction.z()),
                    junction.x()+halfWidth,Math.max(socket.z(),junction.z()));
    }

    private static List<Point> roundSpatial(List<Point> path) {
        var result=new ArrayList<Point>();result.add(path.getFirst());
        double total=0;for(int i=1;i<path.size();i++)total+=length3(path.get(i-1),path.get(i));
        // Keep the same bounded mesh budget even for a wide TV near the 8-block limit.
        int linearBudget=Math.max(1,MAX_ROUTE_POINTS-3-16*Math.max(0,path.size()-2)-(path.size()-1));
        double stepSize=Math.max(STEP,total/linearBudget);
        for(int i=1;i<path.size()-1;i++) {
            Point a=path.get(i-1),b=path.get(i),c=path.get(i+1);
            double before=length3(a,b),after=length3(b,c);
            if(before<EPSILON||after<EPSILON)continue;
            double trim=Math.min(.22,Math.min(before,after)*.28);
            Point entry=blend(b,a,trim/before),leave=blend(b,c,trim/after);
            appendLine(result,result.getLast(),entry,stepSize);
            for(int step=1;step<=16;step++){double t=step/16.0;result.add(blend(blend(entry,b,t),blend(b,leave,t),t));}
        }
        appendLine(result,result.getLast(),path.getLast(),stepSize);
        return result;
    }

    static boolean beforeWall(Point point,int turns,double dx,double dz) {
        Point local=HomeHardwareRenderLayout.rotate(new Point(point.x()-dx,point.y(),point.z()-dz),-turns);
        return local.z()<=1-.005;
    }

    private static void appendLine(List<Point> out,Point a,Point b,double stepSize) {
        double length=length3(a,b);
        int count=Math.max(1,(int)Math.ceil(length/stepSize));
        for(int i=1;i<=count;i++)out.add(blend(a,b,i/(double)count));
    }
    private static double length3(Point a,Point b) {return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}

    private static Rect tvRect(boolean centered, boolean lcd, boolean wideLcd, boolean largeLcd, boolean vintageTv, int turns, double dx, double dz) {
        if(vintageTv){var b=cn.piq.fcarcade.home.VintageTvLayout.bounds(turns);
            return new Rect(dx+b.minX()/16,dz+b.minZ()/16,dx+b.maxX()/16,dz+b.maxZ()/16);}
        // Use the physical two-block footprint, not the centered TV's wider reservation.
        Point a = HomeHardwareRenderLayout.rotate(lcd ? new Point(largeLcd?-.5:0,0,4.8/16) : new Point(0, 0, 0), turns);
        Point b = HomeHardwareRenderLayout.rotate(lcd ? new Point(largeLcd||wideLcd?1.5:1,0,11.2/16) : new Point(2, 0, 2), turns);
        Point offset = HomeHardwareRenderLayout.tvOffset(turns, centered && !lcd);
        return new Rect(dx + offset.x() + Math.min(a.x(), b.x()), dz + offset.z() + Math.min(a.z(), b.z()),
                dx + offset.x() + Math.max(a.x(), b.x()), dz + offset.z() + Math.max(a.z(), b.z()));
    }

    private static Point exit(Point socket, Rect housing, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new Point(Math.min(socket.x() - EXIT, housing.minX() - EXIT), socket.y(), socket.z());
            case 2 -> new Point(socket.x(), socket.y(), Math.min(socket.z() - EXIT, housing.minZ() - EXIT));
            case 3 -> new Point(Math.max(socket.x() + EXIT, housing.maxX() + EXIT), socket.y(), socket.z());
            default -> new Point(socket.x(), socket.y(), Math.max(socket.z() + EXIT, housing.maxZ() + EXIT));
        };
    }

    private static Point lowerJunction(Point point, double support) {
        // Molded plugs still leave the socket axially. Only the flexible fan-out droops.
        return new Point(point.x(), Math.max(support, point.y() - .15), point.z());
    }

    private static void addCorners(List<Point> nodes, Rect own, Rect other) {
        for (double x : new double[]{own.minX(), own.maxX()})
            for (double z : new double[]{own.minZ(), own.maxZ()}) {
                Point corner = new Point(x, 0, z);
                if (!other.contains(corner)) nodes.add(corner);
            }
    }

    private static List<Point> shortestPath(List<Point> nodes, Rect first, Rect second) {
        return shortestPath(nodes,first,second,false);
    }

    private static ArrayList<Point> orthogonalNodes(Point from,Point to,Rect first,Rect second,boolean includeSecond,List<Rect> leadGuards) {
        var nodes=new ArrayList<Point>();nodes.add(from);nodes.add(to);
        var xs=new java.util.TreeSet<Double>();var zs=new java.util.TreeSet<Double>();
        xs.add(from.x());xs.add(to.x());zs.add(from.z());zs.add(to.z());
        var obstacles=new ArrayList<Rect>();obstacles.add(first);if(includeSecond)obstacles.add(second);obstacles.addAll(leadGuards);
        for(Rect rect:obstacles) {
            xs.add(rect.minX());xs.add(rect.maxX());zs.add(rect.minZ());zs.add(rect.maxZ());
        }
        for(double x:xs)for(double z:zs) {
            Point p=new Point(x,from.y(),z);
            if(distance(p,from)>EPSILON&&distance(p,to)>EPSILON&&obstacles.stream().noneMatch(r->r.contains(p)))nodes.add(p);
        }
        return nodes; // At most 100 nodes; built only when the cached cable changes.
    }

    private static List<Point> shortestPath(List<Point> nodes, Rect first, Rect second,boolean orthogonal) {
        return shortestPath(nodes,first,second,orthogonal,null,null,List.of());
    }
    private static List<Point> shortestPath(List<Point> nodes, Rect first, Rect second,boolean orthogonal,
                                            Point firstDirection,Point lastDirection,List<Rect> leadGuards) {
        int count = nodes.size();
        double[] distances = new double[count]; Arrays.fill(distances, Double.POSITIVE_INFINITY);
        int[] previous = new int[count]; Arrays.fill(previous, -1);
        boolean[] visited = new boolean[count]; distances[0] = 0;
        for (int step = 0; step < count; step++) {
            int at = -1;
            for (int i = 0; i < count; i++)
                if (!visited[i] && (at < 0 || distances[i] < distances[at])) at = i;
            if (at < 0 || !Double.isFinite(distances[at])) return List.of();
            if (at == 1) break;
            visited[at] = true;
            for (int next = 0; next < count; next++) {
                if(at==0&&firstDirection!=null&&directionDot(nodes.get(at),nodes.get(next),firstDirection)<-EPSILON)continue;
                if(next==1&&lastDirection!=null&&directionDot(nodes.get(next),nodes.get(at),lastDirection)<-EPSILON)continue;
                if(orthogonal&&Math.abs(nodes.get(at).x()-nodes.get(next).x())>EPSILON
                        &&Math.abs(nodes.get(at).z()-nodes.get(next).z())>EPSILON)continue;
                if (visited[next] || next == at || intersects(nodes.get(at), nodes.get(next), first)
                        || intersects(nodes.get(at), nodes.get(next), second)) continue;
                boolean crossesLead=false;
                for(Rect lead:leadGuards)if(intersects(nodes.get(at),nodes.get(next),lead)){crossesLead=true;break;}
                if(crossesLead)continue;
                // Equal-length grid routes should use fewer turns, not a small
                // staircase merely because its nodes happened to sort first.
                double length = distances[at] + distance(nodes.get(at), nodes.get(next)) + (orthogonal ? .001 : 0);
                if (length + EPSILON < distances[next]) { distances[next] = length; previous[next] = at; }
            }
        }
        if (!Double.isFinite(distances[1])) return List.of();
        var reversed = new ArrayList<Point>();
        for (int at = 1; at >= 0; at = previous[at]) reversed.add(nodes.get(at));
        // Drop collinear grid intersections before rounding; otherwise a short
        // artificial segment would shrink a visible corner into a sharp kink.
        var path=new ArrayList<Point>();
        for(Point point:reversed.reversed()) {
            while(path.size()>1) {
                Point a=path.get(path.size()-2),b=path.getLast();
                double cross=(b.x()-a.x())*(point.z()-b.z())-(b.z()-a.z())*(point.x()-b.x());
                double dot=(b.x()-a.x())*(point.x()-b.x())+(b.z()-a.z())*(point.z()-b.z());
                if(Math.abs(cross)>EPSILON||dot<0)break;
                path.removeLast();
            }
            path.add(point);
        }
        return path;
    }

    private static double directionDot(Point from,Point to,Point direction) {
        return (to.x()-from.x())*direction.x()+(to.z()-from.z())*direction.z();
    }

    /** Segment versus open rectangle interior. Inflated boundary/corner contact is safe. */
    private static boolean intersects(Point a, Point b, Rect rect) {
        double low = 0, high = 1;
        double[] origin = {a.x(), a.z()}, delta = {b.x() - a.x(), b.z() - a.z()};
        double[] min = {rect.minX() + EPSILON, rect.minZ() + EPSILON};
        double[] max = {rect.maxX() - EPSILON, rect.maxZ() - EPSILON};
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(delta[axis]) < EPSILON) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
            } else {
                double t1 = (min[axis] - origin[axis]) / delta[axis], t2 = (max[axis] - origin[axis]) / delta[axis];
                low = Math.max(low, Math.min(t1, t2)); high = Math.min(high, Math.max(t1, t2));
                if (low > high) return false;
            }
        }
        return true;
    }

    private static List<Point> sample(Point start, Point end, List<Point> path, double support, boolean sameBase) {
        double total = 0;
        for (int i = 1; i < path.size(); i++) total += distance(path.get(i - 1), path.get(i));
        var result = new ArrayList<Point>(); result.add(start); result.add(path.getFirst());
        double travelled = 0;
        Point from = path.getFirst(), to = path.getLast();
        double fromDrop = Math.min(total * .4, .28 + Math.max(0,from.y()-support) * .75);
        double toDrop = Math.min(total * .4, .28 + Math.max(0,to.y()-support) * .75);
        double bridgeSag = Math.min(.25,.04+total*.025);
        for (int i = 1; i < path.size(); i++) {
            Point a = path.get(i - 1), b = path.get(i);
            double length = distance(a, b);
            int steps = Math.max(1, (int) Math.ceil(length / STEP));
            if (result.size() + steps + 1 > MAX_ROUTE_POINTS) return List.of();
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                double along = travelled + length * t;
                double y = support + Math.max(
                        Math.max(0,from.y()-support) * remainingLift(along,fromDrop),
                        Math.max(0,to.y()-support) * remainingLift(total-along,toDrop));
                if (!sameBase) {
                    double progress = total > EPSILON ? along/total : t;
                    y = Math.max(support,start.y()+(end.y()-start.y())*progress
                            -4*bridgeSag*progress*(1-progress));
                }
                result.add(new Point(a.x() + (b.x() - a.x()) * t, y, a.z() + (b.z() - a.z()) * t));
            }
            travelled += length;
        }
        // Preserve flexible junctions and exact sockets, including zero-length paths.
        result.set(result.size() - 1, path.getLast());
        result.add(end);
        return List.copyOf(result);
    }

    private static double remainingLift(double distance, double length) {
        if (length <= EPSILON) return distance <= EPSILON ? 1 : 0;
        double t = Math.max(0, Math.min(1, distance/length));
        return 1 - t*t*(3-2*t);
    }

    private static double distance(Point a, Point b) { return Math.hypot(b.x() - a.x(), b.z() - a.z()); }
}
