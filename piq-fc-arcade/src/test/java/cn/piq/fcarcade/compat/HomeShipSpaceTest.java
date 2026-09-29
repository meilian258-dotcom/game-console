package cn.piq.fcarcade.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class HomeShipSpaceTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static HomeShipSpace.Frame frame(UUID id) {
        return new HomeShipSpace.Frame(id,new Vec3(-134,71,992),new Vec3(160012,64,160045),
                new Quaterniond().rotationXYZ(.42,-1.27,.23));
    }
    static void near(Vec3 a,Vec3 b) {
        assertEquals(a.x,b.x,.000000001); assertEquals(a.y,b.y,.000000001); assertEquals(a.z,b.z,.000000001);
    }
    @Test void missingSableNeverLoadsOptionalClassesAndKeepsGroundIdentity() throws Exception {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("dev.ryanhcode.sable"))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        var access = HomeShipSpace.load(loader);
        assertSame(HomeShipSpace.GROUND,access.at(null,null,Float.NaN));
        assertEquals(java.util.List.of(HomeShipSpace.GROUND),access.rayFrames(null,Vec3.ZERO,new Vec3(1,0,0)));
        Vec3 p = new Vec3(-24,50,200); near(p,HomeShipSpace.GROUND.toStorage(p)); near(p,HomeShipSpace.GROUND.toWorld(p));
    }
    @Test void translationPitchYawRollAndRenderInverseRoundTrip() {
        var f=frame(A); Vec3 socket=new Vec3(160012.3,64.5,160045.75),grip=socket.add(2.2,1.1,-.6);
        near(socket,f.toStorage(f.toWorld(socket))); near(grip,f.toStorage(f.toWorld(grip)));
        near(grip.subtract(socket),f.toStorage(f.toWorld(grip)).subtract(socket));
        assertEquals(socket.distanceToSqr(grip),f.toWorld(socket).distanceToSqr(f.toWorld(grip)),1e-8);
        var forward=f.toWorld(grip).subtract(f.toWorld(socket)).normalize();
        near(grip.subtract(socket).normalize(),f.directionToStorage(forward));
    }
    @Test void movementDoesNotChangeBindingButAssemblyAndOtherShipsDo() {
        var f=frame(A);
        var moved=new HomeShipSpace.Frame(A,new Vec3(400,199,-72),new Vec3(160012,64,160045),new Quaterniond().rotationXYZ(-.5,.1,2));
        assertTrue(HomeShipSpace.canLink(true,f,moved));
        assertTrue(HomeShipSpace.bindingMatches(moved,A));
        assertFalse(HomeShipSpace.canLink(true,f,frame(B)));
        assertFalse(HomeShipSpace.canLink(true,f,HomeShipSpace.GROUND));
        assertFalse(HomeShipSpace.canLink(true,HomeShipSpace.GROUND,f));
        assertFalse(HomeShipSpace.bindingMatches(f,null)); // Old ground NBT assembled into a ship.
        assertFalse(HomeShipSpace.bindingMatches(HomeShipSpace.GROUND,A)); // Disassembled ship NBT.
        assertFalse(HomeShipSpace.bindingMatches(frame(B),A));
    }
    @Test void renderRotationIsDefensivelyCopied() {
        var f=frame(A);var point=new Vec3(160013,66,160045);var before=f.toWorld(point);
        f.rotation().identity();
        near(before,f.toWorld(point));
        assertNotEquals(new Quaterniond(),f.rotation());
    }
    @Test void unsupportedConsolesOnlyRetainOriginalGroundBehaviorAndFailuresClose() {
        assertTrue(HomeShipSpace.canLink(false,HomeShipSpace.GROUND,HomeShipSpace.GROUND));
        assertFalse(HomeShipSpace.canLink(false,frame(A),frame(A)));
        assertFalse(HomeShipSpace.canLink(true,null,frame(A)));
        assertFalse(HomeShipSpace.canLink(true,frame(A),null));
        assertFalse(HomeShipSpace.bindingMatches(null,null));
        assertThrows(IllegalArgumentException.class,()->new HomeShipSpace.Frame(A,Vec3.ZERO,Vec3.ZERO,new Quaterniond(0,0,0,0)));
        assertThrows(IllegalArgumentException.class,()->new HomeShipSpace.Frame(A,new Vec3(Double.NaN,0,0),Vec3.ZERO,new Quaterniond()));
    }
}
