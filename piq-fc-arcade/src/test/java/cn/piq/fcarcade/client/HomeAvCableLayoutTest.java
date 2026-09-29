package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.HomeConsoleLayout;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HomeAvCableLayoutTest {
    @Test void allFacingsBothConsoleFamiliesAndCenteredVariantsAvoidBothHousings() {
        int checked = 0;
        for (boolean subor : new boolean[]{false, true}) for (boolean centered : new boolean[]{false, true})
            for (int console = 0; console < 4; console++) for (int tv = 0; tv < 4; tv++)
                for (int[] offset : new int[][]{{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {-4, -4}}) {
                    var route = HomeAvCableLayout.route(subor, console, centered, tv, offset[0], 0, offset[1]);
                    assertFalse(route.isEmpty(), "Separated housings should have a route");
                    verifyRoute(route, subor, console, centered, tv, offset[0], 0, offset[1]); checked++;
                }
        assertEquals(384, checked);
    }

    @Test void unequalHeightsRespectLowerSupportingBaseWithoutMovingSockets() {
        for (boolean subor : new boolean[]{false, true}) for (double dy : new double[]{-2, 0, 2}) {
            var route = HomeAvCableLayout.route(subor, 0, true, 2, 4, dy, 0);
            assertFalse(route.isEmpty()); verifyRoute(route, subor, 0, true, 2, 4, dy, 0);
        }
    }

    @Test void eachStubExitsItsOwnRearInsteadOfTakingAShortcutThroughItsBody() {
        for (int turns = 0; turns < 4; turns++) {
            var route = HomeAvCableLayout.route(false, turns, false, turns, 4, 0, 4);
            Point start = route.getFirst(), stub = route.get(1);
            Point end = route.getLast(), remote = route.get(route.size() - 2);
            Point[] localHeads=HomeAvCableMesh.consoleSockets(false,false,turns);
            Point[] remoteHeads=HomeAvCableMesh.tvSockets(false,turns,new Point(4,0,4));
            Point localCenter=mean(localHeads),remoteCenter=mean(remoteHeads);
            if (turns == 0 || turns == 2) {
                assertEquals(localCenter.x(), stub.x(),1e-9); assertEquals(remoteCenter.x(), remote.x(),1e-9);
                assertTrue((stub.z() - start.z()) * (turns == 0 ? 1 : -1) > 0);
                assertTrue((remote.z() - end.z()) * (turns == 0 ? 1 : -1) > 0);
            } else {
                assertEquals(localCenter.z(), stub.z(),1e-9); assertEquals(remoteCenter.z(), remote.z(),1e-9);
                assertTrue((stub.x() - start.x()) * (turns == 3 ? 1 : -1) > 0);
                assertTrue((remote.x() - end.x()) * (turns == 3 ? 1 : -1) > 0);
            }
            assertTrue(stub.y() <= start.y()); assertTrue(remote.y() < end.y());
            assertTrue(stub.y() >= HomeAvCableLayout.TABLE_CENTERLINE_CLEARANCE);
            assertTrue(remote.y() >= HomeAvCableLayout.TABLE_CENTERLINE_CLEARANCE);
        }
    }

    @Test void centeredTvUsesTwoBlockPhysicalWidthNotThreeBlockReservation() {
        var route = HomeAvCableLayout.route(false, 0, true, 0, 3, 0, 0);
        // Rounded routes need not be a straight chord. They must still be allowed
        // through the obsolete empty half-cell instead of treating it as TV body.
        assertTrue(route.stream().anyMatch(point -> point.x() > 1.96+1e-8 && point.x() < 2.46-1e-8
                && point.z() > -.04+1e-8 && point.z() < 2.04-1e-8),
                "Do not reserve the empty extra half-block left of the centered TV");
        verifyRoute(route, false, 0, true, 0, 3, 0, 0);
    }

    @Test void overlappingOrRearBlockedSocketsHideRatherThanCrossTheOtherHousing() {
        assertTrue(HomeAvCableLayout.route(false, 0, false, 0, 0, 0, 0).isEmpty());
        assertTrue(HomeAvCableLayout.route(false, 0, false, 0, 0, 0, 1).isEmpty());
        assertTrue(HomeAvCableLayout.route(true, 0, false, 0, 0, 0, 0).isEmpty());
    }

    @Test void invalidOrUnlinkedDistanceInputsAreBoundedAndOutputCannotBeMutated() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 9})
            assertTrue(HomeAvCableLayout.route(false, 0, false, 0, invalid, 0, 0).isEmpty());
        var route = HomeAvCableLayout.route(false, 0, false, 0, 8, 0, 0);
        assertFalse(route.isEmpty()); assertTrue(route.size() <= HomeAvCableLayout.MAX_ROUTE_POINTS);
        assertThrows(UnsupportedOperationException.class, () -> route.add(new Point(0, 0, 0)));
        assertEquals(route, HomeAvCableLayout.route(false, 4, false, -4, 8, 0, 0));
    }

    private static void verifyRoute(List<Point> route, boolean subor, int consoleTurns, boolean centered, int tvTurns,
                                    double dx, double dy, double dz) {
        Point start = HomeHardwareRenderLayout.rotate(subor
                ? new Point(HomeConsoleLayout.AV_X, HomeConsoleLayout.AV_Y, HomeConsoleLayout.AV_Z)
                : HomeHardwareRenderLayout.CONSOLE_CABLE, consoleTurns);
        Point remote = HomeHardwareRenderLayout.tvCable(tvTurns, centered);
        Point end = new Point(dx + remote.x(), dy + remote.y(), dz + remote.z());
        assertEquals(start, route.getFirst()); assertEquals(end, route.getLast());
        assertTrue(route.size() <= HomeAvCableLayout.MAX_ROUTE_POINTS);
        var bounds = HomeConsoleLayout.suborBounds(consoleTurns);
        double[] console = subor ? new double[]{bounds.minX()/16, bounds.minZ()/16, bounds.maxX()/16, bounds.maxZ()/16}
                : new double[]{0, 0, 1, 1};
        // Independently rotate all four physical TV corners; do not use the route's rectangle helper.
        double[] tv = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        Point shift = HomeHardwareRenderLayout.tvOffset(tvTurns, centered);
        for (int x : new int[]{0, 2}) for (int z : new int[]{0, 2}) {
            Point corner = HomeHardwareRenderLayout.rotate(new Point(x, 0, z), tvTurns);
            tv[0] = Math.min(tv[0], dx + shift.x() + corner.x()); tv[2] = Math.max(tv[2], dx + shift.x() + corner.x());
            tv[1] = Math.min(tv[1], dz + shift.z() + corner.z()); tv[3] = Math.max(tv[3], dz + shift.z() + corner.z());
        }
        for (int segment = 1; segment < route.size(); segment++) {
            Point a = route.get(segment - 1), b = route.get(segment);
            for (int sample = 0; sample <= 64; sample++) {
                double t = sample / 64.0;
                Point point = new Point(a.x() + (b.x()-a.x())*t, a.y() + (b.y()-a.y())*t, a.z() + (b.z()-a.z())*t);
                assertTrue(Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z()));
                assertTrue(point.y() >= Math.min(0,dy) + HomeAvCableLayout.TABLE_CENTERLINE_CLEARANCE - 1e-9);
                if (segment != 1) assertFalse(inside(point, console), "Cable enters console outside its own stub");
                if (segment != route.size() - 1) assertFalse(inside(point, tv), "Cable enters TV outside its own stub");
            }
        }
    }

    @Test void longCableRestsOnTableRatherThanFloatingAtTheLowerSocket() {
        for (int type=0;type<3;type++) for (boolean lcd:new boolean[]{false,true}) {
            var route=HomeAvCableLayout.route(type!=0,type==2,0,false,lcd,0,4,0,0);
            assertFalse(route.isEmpty());
            long onTable=route.stream().filter(p -> Math.abs(p.y()-.027)<1e-9).count();
            assertTrue(onTable > (route.size()-2)*.60,"Most of a long same-level cable must rest near its supporting table");
            assertTrue(route.stream().mapToDouble(Point::y).min().orElseThrow()<route.getFirst().y());
            assertTrue(route.get(route.size()-2).y()<route.getLast().y(),"TV fan-out should visibly droop after its plug");
        }
    }

    @Test void restingCenterlineLeavesSpaceForTheWholeSolidPipe() {
        assertEquals(HomeAvCableMesh.TRUNK_RADIUS + .004,HomeAvCableLayout.TABLE_CENTERLINE_CLEARANCE,1e-12);
    }

    private static boolean inside(Point point, double[] rect) {
        return point.x() > rect[0] + 1e-8 && point.x() < rect[2] - 1e-8
                && point.z() > rect[1] + 1e-8 && point.z() < rect[3] - 1e-8;
    }
    private static Point mean(Point[] p) {
        double x=0,y=0,z=0;for(Point point:p){x+=point.x();y+=point.y();z+=point.z();}
        return new Point(x/p.length,y/p.length,z/p.length);
    }

    public static void main(String[] args) throws Exception {
        int count = 0; var test = new HomeAvCableLayoutTest();
        for (var method : HomeAvCableLayoutTest.class.getDeclaredMethods())
            if (method.isAnnotationPresent(Test.class)) { method.invoke(test); count++; }
        System.out.println("Passed " + count + " AV routing checks.");
    }
}
