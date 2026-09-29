package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeAvCableMesh.Quad;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.home.HomeConsoleLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the generated solid surfaces, not a second implementation of the route planner. */
class HomeAvCableMeshTest {
    private static final double EPS = 1e-8;
    @Test void compactSuborKeepsThreeAvHeadsWithinItsNewRearAndAllFacingsRemainVisible() {
        for(int a=0;a<4;a++)for(int b=0;b<4;b++) {
            var sockets=HomeAvCableMesh.consoleSockets(true,true,true,a);
            assertEquals(3,sockets.length);
            for(int channel=0;channel<3;channel++)assertPoint(rotate(model(24.32-1.6*channel,1.05,14.9056),a),sockets[channel]);
            var quads=HomeAvCableMesh.build(true,true,a,false,false,false,false,false,b,4,0,0,true);
            assertFalse(quads.isEmpty());
            assertTrue(quads.size()<HomeAvCableMesh.MAX_QUADS);
            for(var q:quads)for(var p:vertices(q))assertTrue(p.y()>=-EPS);
        }
    }
    private record Setup(int console, int consoleTurns, int tv, int tvTurns, double dx, double dy, double dz) {
        boolean subor() { return console != 0; }
        boolean wide() { return console == 2; }
        boolean centered() { return tv == 1; }
        boolean lcd() { return tv >= 2 && tv <= 4; }
        boolean wideLcd() { return tv == 3; }
        boolean largeLcd() { return tv == 4; }
        boolean vintage() { return tv == 5; }
        List<Quad> mesh() { return HomeAvCableMesh.build(subor(), wide(), consoleTurns, centered(), lcd(), wideLcd(), largeLcd(), vintage(), tvTurns, dx, dy, dz); }
        List<Point> route() { return HomeAvCableLayout.route(subor(), wide(), consoleTurns, centered(), lcd(), wideLcd(), largeLcd(), vintage(), tvTurns, dx, dy, dz); }
    }

    @Test void socketsMatchReviewedModelsForThreeConsoleAndThreeTvVariantsInFourFacings() {
        double[][][] console = {
                {{8,2.28,14.97}},
                {{12.77,.50175,12.68},{12.14,.50175,12.68},{10.25,.50175,12.68}},
                {{24.32,1.05,23.632},{22.72,1.05,23.632},{21.12,1.05,23.632}}};
        Point offset = new Point(4,2,-3);
        for (int type = 0; type < 3; type++) for (int turns = 0; turns < 4; turns++) {
            Point[] actual = HomeAvCableMesh.consoleSockets(type != 0, type == 2, turns);
            assertEquals(type == 0 ? 1 : 3, actual.length);
            for (int channel = 0; channel < actual.length; channel++)
                assertPoint(rotate(model(console[type][channel]), turns), actual[channel]);
        }
        for (int type = 0; type < 3; type++) for (int turns = 0; turns < 4; turns++) {
            Point[] actual = HomeAvCableMesh.tvSockets(type == 1, type == 2, turns, offset);
            for (int channel = 0; channel < 3; channel++) {
                Point north = type == 2 ? model(5+channel*3,4,8.23) : model(12+channel*2.86,8.29,28.92);
                if (type == 1) north = add(north,new Point(-.5,0,0));
                assertPoint(add(rotate(north,turns),offset), actual[channel]);
            }
        }
    }

    @Test void eachEndHasThreeColoredSolidPlugsAndExactlyOnePlugOfEachColor() {
        for (int console = 0; console < 3; console++) for (int tv = 0; tv < 6; tv++) {
            Setup setup = new Setup(console,0,tv,0,4,0,0);
            var mesh = setup.mesh(); assertFalse(mesh.isEmpty());
            Point[][] sockets = {HomeAvCableMesh.consoleSockets(setup.subor(),setup.wide(),0),
                    HomeAvCableMesh.tvSockets(setup.centered(),setup.lcd(),setup.wideLcd(),setup.largeLcd(),setup.vintage(),0,new Point(4,0,0))};
            int[] colors = {HomeAvCableMesh.YELLOW,HomeAvCableMesh.WHITE,HomeAvCableMesh.RED};
            for (int color : colors) assertEquals(console == 0 ? 48 : 96, mesh.stream().filter(q -> q.color() == color).count());
            for (int end = 0; end < 2; end++) for (int channel = 0; channel < 3; channel++) {
                if (console == 0 && end == 0) continue; // FC48: a metal coax head, not colored RCA fan-out.
                double scale = end == 0 && console == 1 ? .48 : 1;
                Point socket = sockets[end][channel];
                double min = socket.z()+.025*scale, max = socket.z()+.12*scale;
                final int color = colors[channel];
                long surfaces = mesh.stream().filter(q -> q.color() == color)
                        .filter(q -> vertices(q).stream().allMatch(p -> p.z() >= min-EPS && p.z() <= max+EPS
                                && Math.abs(p.x()-socket.x()) <= .039*scale+EPS)).count();
                assertEquals(48,surfaces,"One colored barrel plus molded rib at this exact socket");
            }
        }
    }

    @Test void newCenteredLargeLcdAndVintageSocketsMatchIndependentModelContract() {
        Point offset=new Point(3,2,-4);
        for(int type=3;type<6;type++)for(int turns=0;turns<4;turns++) {
            var sockets=HomeAvCableMesh.tvSockets(false,type!=5,type==3,type==4,type==5,turns,offset);
            for(int channel=0;channel<3;channel++) {
                Point p=type==5?model(9.5-channel*2,3.1,14.04):model((type==3?9:5)+channel*3,4,8.23);
                assertPoint(add(rotate(p,turns),offset),sockets[channel]);
            }
        }
    }

    @Test void threeBranchesMeetEachTrunkEndAtTheSpecifiedSolidRadii() {
        assertEquals(.023,HomeAvCableMesh.TRUNK_RADIUS); assertEquals(.011,HomeAvCableMesh.BRANCH_RADIUS);
        for (int type = 0; type < 3; type++) {
            Setup setup = new Setup(type,0,1,0,4,0,0); var mesh = setup.mesh(); var route = setup.route();
            for (int end = 0; end < 2; end++) {
                Point junction = end == 0 ? route.get(1) : route.get(route.size()-2);
                double branch = type==0&&end==0 ? .023 : .011*(type == 1 && end == 0 ? .65 : 1);
                Point neighbor=end==0?route.get(2):route.get(route.size()-3);
                Point expected=unit(subtract(neighbor,junction));
                long branches = mesh.stream().filter(q -> close(q.a(),junction) && close(q.d(),junction))
                        .filter(q -> Math.abs(distance(q.b(),junction)-branch) < EPS
                                && Math.abs(distance(q.c(),junction)-branch) < EPS && close(q.normal(),expected)).count();
                long trunk = mesh.stream().filter(q -> close(q.a(),junction) && close(q.d(),junction))
                        .filter(q -> Math.abs(distance(q.b(),junction)-.023) < EPS && !close(q.normal(),expected)).count();
                assertEquals((type==0&&end==0?1L:3L)*HomeAvCableMesh.SIDES,branches,"Every physical lead reaches the same junction");
                assertEquals(HomeAvCableMesh.SIDES,trunk,"The trunk meets, rather than floats away from, the branches");
                mesh.stream().filter(q -> close(q.a(),junction) && close(q.d(),junction))
                        .filter(q -> Math.abs(distance(q.b(),junction)-branch)<EPS && close(q.normal(),expected))
                        .forEach(q -> assertPoint(expected,q.normal()));
            }
        }
    }

    @Test void actualTrunkFacesClearBothHousingsAndAllPipeVerticesStayAboveTheTable() {
        int checked = 0;
        for (int console = 0; console < 3; console++) for (int tv = 0; tv < 6; tv++)
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
                for (int[] offset : new int[][]{{4,0},{-4,0},{0,4},{0,-4},{4,4},{-4,-4}}) {
                    Setup setup = new Setup(console,a,tv,b,offset[0],0,offset[1]);
                    assertTrue(verifySurfaces(setup)<25,"Separated cable has a hard pipe elbow: "+setup); checked++;
                }
        assertEquals(1728,checked);
    }

    @Test void nearbyNonoverlappingHardwareDoesNotLetOtherChannelsCutTheForeignHousing() {
        int checked = 0, hidden = 0;
        for (int console = 0; console < 3; console++) for (int tv = 0; tv < 6; tv++)
            for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++)
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    Setup setup = new Setup(console,a,tv,b,x,0,z);
                    if (overlap(consoleBox(setup),tvBox(setup))) continue;
                    // The centered bundle can reject an unsafe placement during route planning,
                    // before the solid-surface guard needs to reject its individual fan-outs.
                    if (setup.route().isEmpty()) { hidden++; continue; }
                    if (setup.mesh().isEmpty()) { hidden++; continue; }
                    verifySurfaces(setup); checked++;
                }
        assertTrue(checked > 500,"Exercise close, valid physical placements, not only widely separated samples");
        assertTrue(hidden > 0,"Placements without a safe complete three-channel bundle must fail closed");
        System.out.println("Close-placement actual surfaces: visible="+checked+", unsafe hidden="+hidden);
    }

    @Test void unsafeRedBranchBesideLcdIsHiddenWithoutChangingThePrimaryPairingInteraction() {
        Setup nearby = new Setup(1,0,2,1,0,0,1);
        assertTrue(nearby.mesh().isEmpty(),"The merged three-channel route must not cross the LCD's conservative housing corner");
        assertFalse(new Setup(1,0,2,1,0,0,3).mesh().isEmpty(),"Moving the same devices apart restores the mesh");
    }

    @Test void unequalHeightsKeepWholePipesAboveTheLowerSupportingPlane() {
        for (int console = 0; console < 3; console++) for (int tv = 0; tv < 6; tv++)
            for (double dy : new double[]{-2,2}) verifySurfaces(new Setup(console,1,tv,3,4,dy,0));
    }

    @Test void geometryIsFiniteBoundedImmutableAndHiddenForInvalidRoutes() {
        Setup setup = new Setup(2,0,2,2,5,0,5); var mesh = setup.mesh();
        assertFalse(mesh.isEmpty()); assertTrue(mesh.size() <= HomeAvCableMesh.MAX_QUADS);
        assertThrows(UnsupportedOperationException.class,() -> mesh.clear());
        for (Quad quad : mesh) {
            for (Point p : vertices(quad)) assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()));
            assertEquals(1,distance(quad.normal(),new Point(0,0,0)),EPS);
        }
        for (double invalid : new double[]{Double.NaN,Double.POSITIVE_INFINITY,9})
            assertTrue(HomeAvCableMesh.build(false,false,0,false,true,0,invalid,0,0).isEmpty());
        assertTrue(HomeAvCableMesh.build(false,false,0,false,false,0,0,0,0).isEmpty());
        assertEquals(mesh,HomeAvCableMesh.build(true,true,4,false,true,-2,5,0,5));
    }

    @Test void renderHotPathUsesInstanceWeakCacheAndSolidQuadsWithoutPerFrameGeometryArrays() throws Exception {
        String renderer = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java"));
        String cable = renderer.substring(renderer.indexOf("private void drawCable("),renderer.indexOf("private record CachedCable"));
        assertTrue(renderer.contains("new java.util.WeakHashMap<>()"));
        assertTrue(cable.indexOf("if (cached == null") < cable.indexOf("HomeAvCableMesh.build("));
        assertTrue(cable.contains("cached.consoleState() != consoleState") && cable.contains("cached.tvState() != tvState"));
        assertTrue(cable.contains("SuborConsoleBlock.wide(consoleState)") && cable.contains("instanceof LcdTvBlock"));
        assertTrue(cable.contains("HomeAvCableRenderer.draw(cached.quads()"));
        String draw = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/HomeAvCableRenderer.java"));
        assertTrue(draw.contains("entityCutoutNoCull(WHITE)")); assertFalse(draw.contains("RenderType.lines"));
        String hot = draw.substring(draw.indexOf("static void draw("));
        assertFalse(hot.contains("new ")); assertFalse(hot.contains(".stream("));
        assertTrue(draw.contains("if (ready) return;") && draw.contains("manager -> ready = false"));
        assertTrue(draw.contains("register(WHITE, new DynamicTexture(image))"));
    }

    private static double verifySurfaces(Setup setup) {
        var route = setup.route(); var mesh = setup.mesh(); assertFalse(route.isEmpty(),setup.toString());
        assertFalse(mesh.isEmpty(),"Separated hardware must remain visible: "+setup);
        int trunkQuads = (route.size()-3)*HomeAvCableMesh.SIDES + 2*HomeAvCableMesh.SIDES;
        int perPlug = (mesh.size()-trunkQuads)/(setup.console == 0 ? 4 : 6);
        int consoleEnd = perPlug*(setup.console == 0 ? 1 : 3);
        Box console = consoleBox(setup), tv = tvBox(setup);
        for (int index = 0; index < mesh.size(); index++) {
            Quad quad = mesh.get(index);
            for (Point point : vertices(quad)) assertTrue(point.y() >= Math.min(0,setup.dy)-EPS,
                    setup+" pipe surface falls through the lower table: "+point);
            boolean trunk = index < trunkQuads;
            boolean consoleFan = index >= trunkQuads && index < trunkQuads+consoleEnd;
            if (trunk || !consoleFan) assertFalse(intersects(quad,console),setup+" quad "+index+" enters console");
            if (trunk || consoleFan) assertFalse(intersects(quad,tv),setup+" quad "+index+" enters TV");
            if (!trunk) {
                // Beyond each molded plug, all branch faces must stay behind their own real rear wall.
                // Only the short axial plug/socket insertion itself is allowed inside the own housing.
                int local = index-trunkQuads-(consoleFan?0:consoleEnd);
                int branchStart = 4*(HomeAvCableMesh.SIDES+2*HomeAvCableMesh.SIDES);
                if (local%perPlug >= branchStart) {
                    int turns = consoleFan ? setup.consoleTurns : setup.tvTurns;
                    Point origin = consoleFan ? new Point(0,0,0) : new Point(setup.dx,setup.dy,setup.dz);
                    double rear = consoleFan ? (setup.console == 0 ? 14.97/16 : setup.wide() ? 23.8/16 : 12.68/16)
                            : setup.vintage() ? 14.04/16 : setup.lcd() ? 8.23/16 : 28.92/16;
                    for (Point p : vertices(quad)) {
                        Point localPoint = rotate(subtract(p,origin),-turns);
                        assertTrue(localPoint.z() >= rear-EPS,setup+" branch returns through own rear wall");
                    }
                }
            }
        }
        for(int end=0;end<2;end++) {
            Point junction=end==0?route.get(1):route.get(route.size()-2);
            Point target=unit(subtract(end==0?route.get(2):route.get(route.size()-3),junction));
            double radius=setup.console==0&&end==0?.023:.011*(setup.console==1&&end==0?.65:1);int caps=0;
            for(Quad q:mesh) if(close(q.a(),junction)&&close(q.d(),junction)&&Math.abs(distance(q.b(),junction)-radius)<EPS&&close(q.normal(),target)) {
                assertPoint(target,q.normal());caps++;
            }
            assertEquals(setup.console==0&&end==0?8:24,caps,"Each real lead terminal ring must meet the trunk with its tangent");
        }
        var centers=new ArrayList<Point>();int segments=route.size()-3;
        for(int i=0;i<segments;i++) {
            Point sum=new Point(0,0,0);for(int side=0;side<8;side++)sum=add(sum,mesh.get(i*8+side).a());
            centers.add(new Point(sum.x()/8,sum.y()/8,sum.z()/8));
        }
        Point sum=new Point(0,0,0);for(int side=0;side<8;side++)sum=add(sum,mesh.get((segments-1)*8+side).d());
        centers.add(new Point(sum.x()/8,sum.y()/8,sum.z()/8));
        double maximum=0;
        for(int i=1;i<centers.size()-1;i++) {
            Point a=unit(subtract(centers.get(i),centers.get(i-1))),b=unit(subtract(centers.get(i+1),centers.get(i)));
            maximum=Math.max(maximum,Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,dot(a,b))))));
        }
        return maximum;
    }

    private record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
    private static Box consoleBox(Setup setup) {
        if (!setup.subor()) return new Box(0,0,0,1,.5,1);
        var b = HomeConsoleLayout.suborBounds(setup.consoleTurns,setup.wide());
        return new Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,b.maxZ()/16);
    }
    private static Box tvBox(Setup setup) {
        if(setup.vintage()) {
            Point a=rotate(new Point(.2/16,0,1.8/16),setup.tvTurns),b=rotate(new Point(15.8/16,14.3/16,14.2/16),setup.tvTurns);
            return new Box(setup.dx+Math.min(a.x(),b.x()),setup.dy,setup.dz+Math.min(a.z(),b.z()),
                    setup.dx+Math.max(a.x(),b.x()),setup.dy+14.3/16,setup.dz+Math.max(a.z(),b.z()));
        }
        double minZ = setup.lcd() ? 4.8/16 : 0, maxZ = setup.lcd() ? 11.2/16 : 2;
        double width = setup.largeLcd() ? 2 : setup.wideLcd() ? 1.5 : setup.lcd() ? 1 : 2, shift = setup.largeLcd() || setup.centered() ? -.5 : 0;
        Point a = rotate(new Point(shift,0,minZ),setup.tvTurns), b = rotate(new Point(width+shift,0,maxZ),setup.tvTurns);
        return new Box(setup.dx+Math.min(a.x(),b.x()),setup.dy,setup.dz+Math.min(a.z(),b.z()),
                setup.dx+Math.max(a.x(),b.x()),setup.dy+(setup.largeLcd()?19.5/16:setup.wideLcd()?15D/16:setup.lcd()?13.5/16:25.4/16),setup.dz+Math.max(a.z(),b.z()));
    }
    private static boolean overlap(Box a,Box b) {
        return a.minX < b.maxX-EPS && a.maxX > b.minX+EPS && a.minZ < b.maxZ-EPS && a.maxZ > b.minZ+EPS;
    }
    private static List<Point> vertices(Quad q) { return List.of(q.a(),q.b(),q.c(),q.d()); }
    private static boolean intersects(Quad q,Box box) {
        return triangleBox(q.a(),q.b(),q.c(),box) || triangleBox(q.a(),q.c(),q.d(),box);
    }
    /** Exact triangle/AABB separating-axis check, including all nine edge-cross-box axes. */
    private static boolean triangleBox(Point a,Point b,Point c,Box box) {
        Point center = new Point((box.minX+box.maxX)/2,(box.minY+box.maxY)/2,(box.minZ+box.maxZ)/2);
        Point half = new Point((box.maxX-box.minX)/2-EPS,(box.maxY-box.minY)/2-EPS,(box.maxZ-box.minZ)/2-EPS);
        Point[] p = {subtract(a,center),subtract(b,center),subtract(c,center)};
        Point[] edges = {subtract(b,a),subtract(c,b),subtract(a,c)};
        Point normal = cross(edges[0],edges[1]); if (dot(normal,normal) < 1e-22) return false;
        Point[] axes = {new Point(1,0,0),new Point(0,1,0),new Point(0,0,1)};
        for (Point axis : axes) if (separated(p,half,axis)) return false;
        if (separated(p,half,normal)) return false;
        for (Point edge : edges) for (Point axis : axes) if (separated(p,half,cross(edge,axis))) return false;
        return true;
    }
    private static boolean separated(Point[] triangle,Point half,Point axis) {
        if (dot(axis,axis) < 1e-22) return false;
        double min = Double.POSITIVE_INFINITY,max = Double.NEGATIVE_INFINITY;
        for (Point p : triangle) { double projection = dot(p,axis); min = Math.min(min,projection); max = Math.max(max,projection); }
        double radius = Math.abs(axis.x())*half.x()+Math.abs(axis.y())*half.y()+Math.abs(axis.z())*half.z();
        return min > radius || max < -radius;
    }
    private static Point model(double... xyz) { return new Point(xyz[0]/16,xyz[1]/16,xyz[2]/16); }
    private static Point rotate(Point p,int turns) {
        for (int i = 0; i < Math.floorMod(turns,4); i++) p = new Point(1-p.z(),p.y(),p.x());
        return p;
    }
    private static Point add(Point a,Point b) { return new Point(a.x()+b.x(),a.y()+b.y(),a.z()+b.z()); }
    private static Point subtract(Point a,Point b) { return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    private static Point cross(Point a,Point b) { return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    private static Point unit(Point p) {double d=distance(p,new Point(0,0,0));return new Point(p.x()/d,p.y()/d,p.z()/d);}
    private static double dot(Point a,Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static double distance(Point a,Point b) { Point d = subtract(a,b); return Math.sqrt(dot(d,d)); }
    private static boolean close(Point a,Point b) { return distance(a,b) < EPS; }
    private static void assertPoint(Point expected,Point actual) {
        assertEquals(expected.x(),actual.x(),EPS); assertEquals(expected.y(),actual.y(),EPS); assertEquals(expected.z(),actual.z(),EPS);
    }
    public static void main(String[] args) throws Exception {
        int count = 0; var test = new HomeAvCableMeshTest();
        for (var method : HomeAvCableMeshTest.class.getDeclaredMethods()) if (method.isAnnotationPresent(Test.class)) {
            if (args.length > 0 && !method.getName().contains(args[0])) continue;
            method.invoke(test); count++; System.out.println("PASS " + method.getName());
        }
        System.out.println("Passed " + count + " actual AV mesh checks.");
    }
}
