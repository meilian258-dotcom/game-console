package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeConsoleLayout;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import java.util.ArrayList;
import java.util.List;

/** Bounded solid cable mesh: FC coax or Subor RCA, with three RCA plugs at the television. */
public final class HomeAvCableMesh {
    public static final double TRUNK_RADIUS = .023, BRANCH_RADIUS = .011;
    public static final int SIDES = 8, MAX_QUADS = 6000;
    public static final int YELLOW = 0xE6B52C, WHITE = 0xDEDFD7, RED = 0xAC2828;
    private static final int RUBBER = 0x222426, METAL = 0xA7A9A8;
    public record Quad(Point a, Point b, Point c, Point d, Point normal, int color) {}
    private HomeAvCableMesh() {}

    public static List<Quad> build(boolean subor, boolean wide, int consoleTurns, boolean centered, int tvTurns,
                                    double dx, double dy, double dz) {
        return build(subor,wide,consoleTurns,centered,false,tvTurns,dx,dy,dz);
    }

    public static List<Quad> build(boolean subor, boolean wide, int consoleTurns, boolean centered, boolean lcd, int tvTurns,
                                    double dx, double dy, double dz) {
        return build(subor,wide,consoleTurns,centered,lcd,false,tvTurns,dx,dy,dz);
    }

    public static List<Quad> build(boolean subor, boolean wide, int consoleTurns, boolean centered,
                                    boolean lcd, boolean wideLcd, int tvTurns, double dx,double dy,double dz) {
        return build(subor,wide,consoleTurns,centered,lcd,wideLcd,false,tvTurns,dx,dy,dz);
    }

    public static List<Quad> build(boolean subor,boolean wide,int consoleTurns,boolean centered,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,int tvTurns,double dx,double dy,double dz) {
        return build(subor,wide,consoleTurns,centered,lcd,wideLcd,largeLcd,false,tvTurns,dx,dy,dz);
    }

    public static List<Quad> build(boolean subor,boolean wide,int consoleTurns,boolean centered,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,boolean vintageTv,int tvTurns,double dx,double dy,double dz) {
        return build(subor,wide,consoleTurns,centered,lcd,wideLcd,largeLcd,vintageTv,tvTurns,dx,dy,dz,false);
    }

    public static List<Quad> build(boolean subor,boolean wide,int consoleTurns,boolean centered,
                                    boolean lcd,boolean wideLcd,boolean largeLcd,boolean vintageTv,int tvTurns,double dx,double dy,double dz,boolean compact) {
        if(vintageTv){lcd=false;wideLcd=false;largeLcd=false;centered=false;}
        lcd |= wideLcd || largeLcd;
        if(largeLcd)wideLcd=false;
        List<Point> route = HomeAvCableLayout.route(subor, wide, consoleTurns, centered, lcd, wideLcd, largeLcd, vintageTv, tvTurns, dx, dy, dz,compact);
        if (route.isEmpty()) return List.of();
        List<Quad> result = new ArrayList<>();
        Housing console = consoleHousing(subor, wide, compact, consoleTurns);
        Housing television = tvHousing(centered, lcd, wideLcd, largeLcd, vintageTv, tvTurns, dx, dy, dz);
        // Replace the two old socket stubs with separate RCA fan-outs. Keep every safe path corner.
        tube(result, route.subList(1, route.size()-1), TRUNK_RADIUS, RUBBER);
        if (!clearOf(result, 0, console.outer()) || !clearOf(result, 0, television.outer())) return List.of();
        Point offset = new Point(dx,dy,dz);
        Point[] consoleSockets = consoleSockets(subor, wide, compact,consoleTurns);
        Point[] tvSockets = tvSockets(centered, lcd, wideLcd, largeLcd, vintageTv, tvTurns, offset);
        if (!plugs(result, consoleSockets, outward(consoleTurns), route.get(1), subor && !wide ? .48 : 1,
                unit(subtract(route.get(2),route.get(1))), console, television.outer(),!subor)) return List.of();
        if (!plugs(result, tvSockets, outward(tvTurns), route.get(route.size()-2), 1,
                unit(subtract(route.get(route.size()-3),route.get(route.size()-2))), television, console.outer())) return List.of();
        double base=Math.min(0,dy);
        for(Quad q:result) if(Math.min(Math.min(q.a().y(),q.b().y()),Math.min(q.c().y(),q.d().y()))<base-1e-9) return List.of();
        if (result.size() > MAX_QUADS) throw new IllegalArgumentException("AV mesh exceeds fixed budget");
        return List.copyOf(result);
    }

    public static Point[] consoleSockets(boolean subor, boolean wide, int turns) {
        return consoleSockets(subor,wide,false,turns);
    }

    public static Point[] consoleSockets(boolean subor, boolean wide, boolean compact, int turns) {
        if (!subor) return new Point[]{HomeHardwareRenderLayout.rotate(HomeHardwareRenderLayout.CONSOLE_CABLE,turns)};
        Point[] sockets = new Point[3];
        for (int channel = 0; channel < 3; channel++) {
            Point local;
            if (subor && wide) local = new Point(HomeConsoleLayout.WIDE_AV_X - channel*HomeConsoleLayout.WIDE_AV_SPACING,
                    HomeConsoleLayout.WIDE_AV_Y, HomeConsoleLayout.suborZ(HomeConsoleLayout.WIDE_AV_Z,compact));
            else if (subor) local = new Point((channel == 2 ? 10.25 : 12.77-channel*.63)/16,
                    HomeConsoleLayout.AV_Y, HomeConsoleLayout.AV_Z);
            else local = new Point(.5 + channel*.10, HomeHardwareRenderLayout.CONSOLE_CABLE.y(),
                    HomeHardwareRenderLayout.CONSOLE_CABLE.z());
            sockets[channel] = HomeHardwareRenderLayout.rotate(local, turns);
        }
        return sockets;
    }

    public static Point[] tvSockets(boolean centered, int turns, Point offset) {
        return tvSockets(centered, false, turns, offset);
    }

    public static Point[] tvSockets(boolean centered, boolean lcd, int turns, Point offset) {
        return tvSockets(centered,lcd,false,turns,offset);
    }

    public static Point[] tvSockets(boolean centered,boolean lcd,boolean wideLcd,int turns,Point offset) {
        return tvSockets(centered,lcd,wideLcd,false,turns,offset);
    }

    public static Point[] tvSockets(boolean centered,boolean lcd,boolean wideLcd,boolean largeLcd,int turns,Point offset) {
        return tvSockets(centered,lcd,wideLcd,largeLcd,false,turns,offset);
    }

    public static Point[] tvSockets(boolean centered,boolean lcd,boolean wideLcd,boolean largeLcd,boolean vintageTv,int turns,Point offset) {
        if(vintageTv){
            Point[] sockets=new Point[3];
            for(int c=0;c<3;c++){var p=cn.piq.fcarcade.home.VintageTvLayout.socket(turns,c);sockets[c]=add(new Point(p.x(),p.y(),p.z()),offset);}
            return sockets;
        }
        lcd |= wideLcd || largeLcd;
        if(largeLcd)wideLcd=false;
        Point[] sockets = new Point[3];
        Point shift = HomeHardwareRenderLayout.tvOffset(turns, centered && !lcd);
        for (int channel = 0; channel < 3; channel++) {
            // Centers measured from the existing TV's yellow, white and red ring meshes.
            Point local = lcd ? new Point(((wideLcd?9:5)+channel*3.0)/16,4.0/16,8.23/16)
                    : new Point((12 + channel*2.86)/16, 8.29/16, 28.92/16);
            sockets[channel] = add(add(HomeHardwareRenderLayout.rotate(local, turns), shift), offset);
        }
        return sockets;
    }

    static boolean plugs(List<Quad> result, Point[] sockets, Point normal, Point junction, double size,
                                 Point trunkTangent, Housing own, Box other) {
        return plugs(result,sockets,normal,junction,size,trunkTangent,own,other,false);
    }
    static boolean plugs(List<Quad> result, Point[] sockets, Point normal, Point junction, double size,
                                 Point trunkTangent, Housing own, Box other,boolean coax) {
        return plugs(result,sockets,normal,junction,size,trunkTangent,own,other,coax,TRUNK_RADIUS);
    }
    static boolean plugs(List<Quad> result, Point[] sockets, Point normal, Point junction, double size,
                                 Point trunkTangent, Housing own, Box other,boolean coax,double cableRadius) {
        int[] colors = {YELLOW, WHITE, RED};
        for (int channel = 0; channel < sockets.length; channel++) {
            int firstPlugQuad = result.size();
            Point socket = sockets[channel];
            Point tip = add(socket, multiply(normal, .025*size));
            Point barrel = add(socket, multiply(normal, .12*size));
            Point tail = add(socket, multiply(normal, .18*size));
            tube(result, List.of(socket,tip), .022*size, METAL);
            tube(result, List.of(tip,barrel), .036*size, coax ? METAL : colors[channel]);
            tube(result, List.of(barrel,tail), .023*size, RUBBER);
            // A molded rib, with the same channel color, makes the barrel readable at block scale.
            tube(result, List.of(add(socket,multiply(normal,.09*size)), add(socket,multiply(normal,.103*size))),
                    .039*size, coax ? METAL : colors[channel]);
            // Only the short axial plug may enter its own socket; no channel may enter the other device.
            if (!clearOf(result, firstPlugQuad, other)) return false;
            // Cubic instead of three quadratic tips colliding at the yellow head:
            // axial departure, shared incoming tangent, then one short straight
            // neck exactly collinear with the first/last trunk segment.
            Point shoulder = add(tail, multiply(normal, .16));
            double neck=.035,approachLength=.12;
            if(trunkTangent.y()>0) {
                double support=Math.min(own.outer().minY(),other.minY())+TRUNK_RADIUS+.004;
                double available=Math.max(0,(junction.y()-support)/trunkTangent.y());
                // A near-zero, immediately rising bridge cannot admit a smooth
                // incoming strand at tabletop height without tunnelling below it.
                if(available<1e-5)return false;
                neck=Math.min(neck,available*.25);
                approachLength=Math.min(approachLength,available-neck);
            }
            Point merge=add(junction,multiply(trunkTangent,-neck));
            Point approach=add(merge,multiply(trunkTangent,-approachLength));
            List<Point> branch = new ArrayList<>();
            for (int step = 0; step <= 32; step++) {
                double t = step/32.0, u = 1-t;
                branch.add(add(add(multiply(tail,u*u*u),multiply(shoulder,3*u*u*t)),
                        add(multiply(approach,3*u*t*t),multiply(merge,t*t*t))));
            }
            if(neck>1e-8)branch.add(junction);
            int firstBranchQuad = result.size();
            // FC is one coaxial cable, not an RCA strand joining a thicker cable.
            tube(result, branch, coax ? cableRadius : BRANCH_RADIUS * Math.max(.65, size), RUBBER);
            if(!coax && channel==0 && neck>1e-5 && cableRadius!=TRUNK_RADIUS)
                tube(result,List.of(merge,junction),BRANCH_RADIUS*Math.max(.65,size),cableRadius,RUBBER);
            if (!clearOf(result, firstBranchQuad, other) || !clearOf(result, firstBranchQuad, own.body())
                    || own.base() != null && !clearOf(result, firstBranchQuad, own.base())) return false;
        }
        return true;
    }

    static record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    static record Housing(Box outer, Box body, Box base) {}

    static Housing consoleHousing(boolean subor, boolean wide, boolean compact,int turns) {
        Box box;
        if (subor) {
            var b = HomeConsoleLayout.suborBounds(turns, wide,compact);
            box = new Box(b.minX()/16, b.minY()/16, b.minZ()/16, b.maxX()/16, b.maxY()/16, b.maxZ()/16);
        } else box = new Box(0, 0, 0, 1, .5, 1);
        return new Housing(box, box, null);
    }

    static Housing tvHousing(boolean centered, boolean lcd, boolean wideLcd, boolean largeLcd, boolean vintageTv, int turns, double dx, double dy, double dz) {
        if(vintageTv){
            var b=cn.piq.fcarcade.home.VintageTvLayout.bounds(0);
            Point offset=new Point(dx,dy,dz);
            return new Housing(orientedBox(new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16),turns,offset),
                    orientedBox(new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,cn.piq.fcarcade.home.VintageTvLayout.socket(0,0).z()),turns,offset),null);
        }
        Point shift = HomeHardwareRenderLayout.tvOffset(turns, centered && !lcd);
        Point offset = new Point(dx+shift.x(), dy, dz+shift.z());
        Box outer = orientedBox(lcd ? new Box(largeLcd?-.5:0,0,4.8/16,largeLcd||wideLcd?1.5:1,(largeLcd?19.5:wideLcd?15:13.5)/16,11.2/16)
                : new Box(0,0,0,2,25.4/16,2), turns, offset);
        if (!lcd) return new Housing(outer,
                orientedBox(new Box(0,0,0,2,25.4/16,28.92/16), turns, offset), null);
        // The LCD's low pedestal reaches behind its thin shell. A branch at socket height
        // may pass above that pedestal, but not through either its body or supporting foot.
        Box body = orientedBox(new Box(largeLcd?-.5:0,2.0/16,6.0/16,largeLcd||wideLcd?1.5:1,(largeLcd?19.5:wideLcd?15:13.5)/16,8.23/16), turns, offset);
        Box base = orientedBox(new Box(largeLcd?-.5:0,0,4.8/16,largeLcd||wideLcd?1.5:1,2.0/16,11.2/16), turns, offset);
        return new Housing(outer, body, base);
    }

    static Box orientedBox(Box north, int turns, Point offset) {
        Point a = HomeHardwareRenderLayout.rotate(new Point(north.minX(), north.minY(), north.minZ()), turns);
        Point b = HomeHardwareRenderLayout.rotate(new Point(north.maxX(), north.maxY(), north.maxZ()), turns);
        return new Box(offset.x()+Math.min(a.x(),b.x()),offset.y()+north.minY(),offset.z()+Math.min(a.z(),b.z()),
                offset.x()+Math.max(a.x(),b.x()),offset.y()+north.maxY(),offset.z()+Math.max(a.z(),b.z()));
    }

    /** Cache-build-only, linear in generated quads. Test actual pipe triangles, not just their centerline. */
    static boolean clearOf(List<Quad> quads, int from, Box box) {
        for (int index = from; index < quads.size(); index++) {
            Quad q = quads.get(index);
            if (triangleBox(q.a(),q.b(),q.c(),box) || triangleBox(q.a(),q.c(),q.d(),box)) return false;
        }
        return true;
    }

    /** Triangle/AABB separating-axis test. Boundary-only contact is not an interior collision. */
    private static boolean triangleBox(Point a, Point b, Point c, Box box) {
        double epsilon = 1e-9;
        if (Math.max(a.x(),Math.max(b.x(),c.x())) <= box.minX()+epsilon
                || Math.min(a.x(),Math.min(b.x(),c.x())) >= box.maxX()-epsilon
                || Math.max(a.y(),Math.max(b.y(),c.y())) <= box.minY()+epsilon
                || Math.min(a.y(),Math.min(b.y(),c.y())) >= box.maxY()-epsilon
                || Math.max(a.z(),Math.max(b.z(),c.z())) <= box.minZ()+epsilon
                || Math.min(a.z(),Math.min(b.z(),c.z())) >= box.maxZ()-epsilon) return false;
        Point center = new Point((box.minX()+box.maxX())/2,(box.minY()+box.maxY())/2,(box.minZ()+box.maxZ())/2);
        Point half = new Point((box.maxX()-box.minX())/2-epsilon,(box.maxY()-box.minY())/2-epsilon,
                (box.maxZ()-box.minZ())/2-epsilon);
        Point p = subtract(a,center), q = subtract(b,center), r = subtract(c,center);
        Point ab = subtract(b,a), bc = subtract(c,b), ca = subtract(a,c), normal = cross(ab,bc);
        if (dot(normal,normal) < 1e-22 || separated(p,q,r,half,normal)) return false;
        return !edgeSeparated(p,q,r,half,ab) && !edgeSeparated(p,q,r,half,bc) && !edgeSeparated(p,q,r,half,ca);
    }

    private static boolean edgeSeparated(Point p,Point q,Point r,Point half,Point edge) {
        return separated(p,q,r,half,new Point(0,edge.z(),-edge.y()))
                || separated(p,q,r,half,new Point(-edge.z(),0,edge.x()))
                || separated(p,q,r,half,new Point(edge.y(),-edge.x(),0));
    }

    private static boolean separated(Point p,Point q,Point r,Point half,Point axis) {
        if (dot(axis,axis) < 1e-22) return false;
        double a = dot(p,axis), b = dot(q,axis), c = dot(r,axis);
        double radius = Math.abs(axis.x())*half.x()+Math.abs(axis.y())*half.y()+Math.abs(axis.z())*half.z();
        return Math.min(a,Math.min(b,c)) > radius || Math.max(a,Math.max(b,c)) < -radius;
    }
    private static double dot(Point a,Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }

    static void tube(List<Quad> out, List<Point> path, double radius, int color) {
        tube(out,path,radius,radius,color);
    }
    static void tube(List<Quad> out, List<Point> path, double startRadius, double endRadius, int color) {
        if (path.size() < 2) return;
        List<Point[]> rings = new ArrayList<>();
        Point previousAcross=null;
        for (int i = 0; i < path.size(); i++) {
            double radius=startRadius+(endRadius-startRadius)*i/(path.size()-1);
            Point tangent = unit(subtract(path.get(Math.min(i+1,path.size()-1)), path.get(Math.max(i-1,0))));
            Point across = unit(cross(tangent, Math.abs(tangent.y()) > .95 ? new Point(1,0,0) : new Point(0,1,0)));
            if(previousAcross!=null) {
                double dot=previousAcross.x()*tangent.x()+previousAcross.y()*tangent.y()+previousAcross.z()*tangent.z();
                Point transported=subtract(previousAcross,multiply(tangent,dot));
                if(transported.x()*transported.x()+transported.y()*transported.y()+transported.z()*transported.z()>1e-10)
                    across=unit(transported);
            }
            previousAcross=across;
            Point up = unit(cross(tangent, across));
            Point[] ring = new Point[SIDES];
            for (int side = 0; side < SIDES; side++) {
                double angle = side * 2*Math.PI/SIDES;
                ring[side] = add(path.get(i), multiply(add(multiply(across,Math.cos(angle)),multiply(up,Math.sin(angle))),radius));
            }
            rings.add(ring);
        }
        for (int i = 1; i < rings.size(); i++) for (int side = 0; side < SIDES; side++) {
            int next = (side+1)%SIDES;
            Point a=rings.get(i-1)[side], b=rings.get(i-1)[next], c=rings.get(i)[next], d=rings.get(i)[side];
            out.add(new Quad(a,b,c,d,unit(cross(subtract(b,a),subtract(d,a))),color));
        }
        for (int side = 0; side < SIDES; side++) {
            int next=(side+1)%SIDES;
            Point first=path.getFirst(), last=path.getLast();
            out.add(new Quad(first,rings.getFirst()[next],rings.getFirst()[side],first,
                    unit(subtract(path.getFirst(),path.get(1))),color));
            out.add(new Quad(last,rings.getLast()[side],rings.getLast()[next],last,
                    unit(subtract(last,path.get(path.size()-2))),color));
        }
    }
    static Point outward(int turns) {
        return switch(Math.floorMod(turns,4)) { case 1 -> new Point(-1,0,0); case 2 -> new Point(0,0,-1);
            case 3 -> new Point(1,0,0); default -> new Point(0,0,1); };
    }
    static Point add(Point a,Point b) { return new Point(a.x()+b.x(),a.y()+b.y(),a.z()+b.z()); }
    static Point subtract(Point a,Point b) { return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    static Point multiply(Point a,double s) { return new Point(a.x()*s,a.y()*s,a.z()*s); }
    private static Point cross(Point a,Point b) { return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    static Point unit(Point a) {
        double length = Math.sqrt(a.x()*a.x()+a.y()*a.y()+a.z()*a.z());
        return length < 1e-12 ? new Point(0,0,1) : multiply(a,1/length);
    }
}
