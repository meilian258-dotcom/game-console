package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.ControllerCableGeometry.Style;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerCableGeometryTest {
    private static final UUID PLAYER = new UUID(1, 2), LEASE = new UUID(3, 4), OTHER = new UUID(5, 6);
    private static final Path ASSETS = Path.of("src/main/resources/assets/piq_fc_arcade");
    private static String asset(String name) throws Exception {
        String jar = System.getProperty("piq.cable.finalJar");
        if (jar == null) return Files.readString(ASSETS.resolve(name));
        try (var zip = new java.util.zip.ZipFile(jar)) {
            return new String(zip.getInputStream(zip.getEntry("assets/piq_fc_arcade/" + name)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static void point(Point p, double x, double y, double z) {
        assertEquals(x, p.x(), 1e-12); assertEquals(y, p.y(), 1e-12); assertEquals(z, p.z(), 1e-12);
    }
    @Test void socketsComeFromBothActualSuborMeshesNotAvOrGunCoordinates() throws Exception {
        for (boolean wide : new boolean[]{false, true}) {
            var json = JsonParser.parseString(asset("meshes/home_subor_sb926" + (wide ? "_wide" : "") + ".json")).getAsJsonObject();
            var sockets = json.getAsJsonObject("metadata").getAsJsonObject("anchors").getAsJsonArray("controller_sockets");
            for (int port = 0; port < 2; port++) {
                var v = sockets.get(port).getAsJsonArray();
                point(ControllerCableGeometry.socket(wide ? Style.SUBOR_WIDE : Style.SUBOR, port, 0),
                        v.get(0).getAsDouble()/16, v.get(1).getAsDouble()/16, v.get(2).getAsDouble()/16);
            }
        }
    }
    @Test void famicomSocketIsFinalRealDockedCordRing() throws Exception {
        for (int port = 0; port < 2; port++) {
            var json = JsonParser.parseString(asset("models/block/home_controller_p" + (port + 1) + "_docked.json")).getAsJsonObject();
            var element = java.util.stream.StreamSupport.stream(json.getAsJsonArray("elements").spliterator(), false)
                    .map(v -> v.getAsJsonObject()).filter(v -> v.get("name").getAsString().endsWith("手柄线·6")).findFirst().orElseThrow();
            var a = element.getAsJsonArray("from"); var b = element.getAsJsonArray("to");
            assertFalse(element.has("rotation"));
            point(ControllerCableGeometry.socket(Style.FAMICOM, port, 0),
                    (a.get(0).getAsDouble()+b.get(0).getAsDouble())/32,
                    (a.get(1).getAsDouble()+b.get(1).getAsDouble())/32, a.get(2).getAsDouble()/16);
        }
    }
    @Test void allEightPortAnchorsRotateExactlyOnceAboutBlockCenter() {
        for (Style style : Style.values()) for (int port=0;port<2;port++) {
            var n=ControllerCableGeometry.socket(style,port,0);
            point(ControllerCableGeometry.socket(style,port,1),1-n.z(),n.y(),n.x());
            point(ControllerCableGeometry.socket(style,port,2),1-n.x(),n.y(),1-n.z());
            point(ControllerCableGeometry.socket(style,port,3),n.z(),n.y(),1-n.x());
            assertEquals(n,ControllerCableGeometry.socket(style,port,4));
            assertEquals(ControllerCableGeometry.socket(style,port,3),ControllerCableGeometry.socket(style,port,-1));
        }
    }
    @Test void invalidSocketsAreNotSilentlyMappedToP1() {
        assertThrows(IllegalArgumentException.class,()->ControllerCableGeometry.socket(Style.SFC,-1,0));
        assertThrows(IllegalArgumentException.class,()->ControllerCableGeometry.socket(Style.FAMICOM,2,0));
        assertThrows(IllegalArgumentException.class,()->ControllerCableGeometry.socket(null,0,0));
    }
    private static int hand(UUID p,UUID lease,UUID actual,int port,UUID main,int mainPort,UUID off,int offPort,double distance,boolean docked) {
        return ControllerCableGeometry.heldHand(p,lease,actual,port,main,mainPort,off,offPort,distance,docked);
    }
    @Test void exactOwnerLeasePortAndEitherPhysicalHandAreRequired() {
        for(int p=0;p<2;p++) {
            assertEquals(0,hand(PLAYER,LEASE,PLAYER,p,LEASE,p,null,-1,36,false));
            assertEquals(1,hand(PLAYER,LEASE,PLAYER,p,null,-1,LEASE,p,36,false));
            assertEquals(-1,hand(PLAYER,LEASE,OTHER,p,LEASE,p,null,-1,1,false));
            assertEquals(-1,hand(PLAYER,LEASE,PLAYER,p,OTHER,p,null,-1,1,false));
            assertEquals(-1,hand(PLAYER,LEASE,PLAYER,p,LEASE,1-p,null,-1,1,false));
        }
    }
    @Test void returnStaleReceiptMissingSyncAndDuplicateHandsNeverDraw() {
        assertEquals(-1,hand(PLAYER,LEASE,PLAYER,0,LEASE,0,null,-1,1,true));
        assertEquals(-1,hand(null,LEASE,PLAYER,0,LEASE,0,null,-1,1,false));
        assertEquals(-1,hand(PLAYER,null,PLAYER,0,LEASE,0,null,-1,1,false));
        assertEquals(-1,hand(PLAYER,LEASE,PLAYER,0,null,-1,null,-1,1,false));
        assertEquals(-1,hand(PLAYER,LEASE,PLAYER,0,LEASE,0,LEASE,0,1,false));
    }
    @Test void sixBlocksIsInclusiveAndNonfiniteCannotEscapeGate() {
        assertEquals(0,hand(PLAYER,LEASE,PLAYER,0,LEASE,0,null,-1,36,false));
        for(double distance:new double[]{Math.nextUp(36d),37,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertEquals(-1,hand(PLAYER,LEASE,PLAYER,0,LEASE,0,null,-1,distance,false));
    }
    @Test void receiptSwapImmediatelyChangesEndpointWithoutCachedOldPlayer() {
        assertEquals(0,hand(PLAYER,LEASE,PLAYER,0,LEASE,0,null,-1,1,false));
        assertEquals(-1,hand(OTHER,OTHER,PLAYER,0,LEASE,0,null,-1,1,false));
        assertEquals(1,hand(OTHER,OTHER,OTHER,0,null,-1,OTHER,0,1,false));
    }
    @Test void boundedSaggedCordHasExactEndpointsAndNoMutableArrays() {
        for(Style style:Style.values()) for(int port=0;port<2;port++) for(int t=0;t<4;t++) for(int yaw=0;yaw<360;yaw+=15) {
            var a=ControllerCableGeometry.socket(style,port,t);
            double r=Math.toRadians(yaw); var b=new Point(.5+6*Math.sin(r),1.67,.5+6*Math.cos(r));
            var line=ControllerCableGeometry.cable(a,b);
            assertTrue(line.size()>=9&&line.size()<=65); assertSame(a,line.getFirst()); assertSame(b,line.getLast());
            for(int i=0;i<line.size();i++) {
                var p=line.get(i);assertTrue(Double.isFinite(p.x()+p.y()+p.z()));
                double fraction=i/(double)(line.size()-1), linearY=a.y()+(b.y()-a.y())*fraction;
                assertTrue(p.y()<=linearY+1e-12); assertTrue(p.y()>=linearY-.450000001);
                assertTrue(Math.abs(p.x())<10&&Math.abs(p.y())<10&&Math.abs(p.z())<10);
            }
            assertThrows(UnsupportedOperationException.class,()->line.add(a));
        }
    }
    @Test void malformedOrUnboundedEndpointsYieldNoVertices() {
        var zero=new Point(0,0,0);
        for(var bad:new Point[]{zero,null,new Point(Double.NaN,0,0),new Point(0,Double.POSITIVE_INFINITY,0),new Point(13,0,0)})
            assertTrue(ControllerCableGeometry.cable(zero,bad).isEmpty());
        assertFalse(ControllerCableGeometry.cable(zero,new Point(12,0,0)).isEmpty());
    }
    @Test void thirdPersonEndpointsFollowBodyFacingAndHandWithoutHeadYaw() {
        for(boolean crouch:new boolean[]{false,true}) for(boolean two:new boolean[]{false,true}) for(boolean right:new boolean[]{false,true}) {
            var n=ControllerCableGeometry.thirdGrip(0,right,two,crouch);
            for(int turn=0;turn<4;turn++) {
                var p=ControllerCableGeometry.thirdGrip(turn*90,right,two,crouch);
                double r=Math.toRadians(turn*90);
                point(p,n.x()*Math.cos(r)-n.z()*Math.sin(r),n.y(),n.z()*Math.cos(r)+n.x()*Math.sin(r));
            }
            if(two)assertEquals(0,n.x(),1e-12);
            else assertEquals(right?-.30:.30,n.x());
            assertTrue(n.z()>0);assertTrue(n.y()>0.9&&n.y()<1.2);
        }
        assertNull(ControllerCableGeometry.thirdGrip(Double.NaN,true,true,false));
    }
}
