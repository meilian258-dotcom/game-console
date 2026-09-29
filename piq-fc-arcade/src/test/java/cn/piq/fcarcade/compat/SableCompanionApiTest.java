package cn.piq.fcarcade.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.jar.JarFile;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/** Real shipped API/pose checks, not a Minecraft or active ship simulation. No dependency is added to the MOD. */
class SableCompanionApiTest {
    @Test void realCompanion16PoseAndPublicSignaturesMatchBridge() throws Exception {
        Path libs=Path.of("../piq-coaster-scan-throttle/libs");
        Path companion=libs.resolve("sable-companion-common-1.21.1-1.6.0.jar"),sable=libs.resolve("sable-2.0.3.jar");
        Assumptions.assumeTrue(Files.isRegularFile(companion)&&Files.isRegularFile(sable),"Optional local Sable verification libraries unavailable");
        try(var jar=new JarFile(sable.toFile())) {
            assertNotNull(jar.getEntry("dev/ryanhcode/sable/Sable.class"));
            assertNotNull(jar.getEntry("META-INF/services/dev.ryanhcode.sable.companion.SableCompanion"));
            assertEquals("dev.ryanhcode.sable.ActiveSableCompanion",new String(jar.getInputStream(
                    jar.getEntry("META-INF/services/dev.ryanhcode.sable.companion.SableCompanion")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim());
        }
        try(var loader=new URLClassLoader(new java.net.URL[]{companion.toUri().toURL()},getClass().getClassLoader())) {
            var api=Class.forName("dev.ryanhcode.sable.companion.SableCompanion",false,loader);
            var sub=Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess",false,loader);
            var client=Class.forName("dev.ryanhcode.sable.companion.ClientSubLevelAccess",false,loader);
            var poseApi=Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc",false,loader);
            var boxApi=Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc",false,loader);
            assertNotNull(api.getField("INSTANCE")); assertNotNull(api.getMethod("getContaining",Level.class,Vec3i.class));
            assertNotNull(api.getMethod("isInPlotGrid",Level.class,Vec3i.class));
            assertNotNull(api.getMethod("getAllIntersecting",Level.class,boxApi));
            assertNotNull(api.getMethod("getEyePositionInterpolated",net.minecraft.world.entity.Entity.class,float.class));
            assertNotNull(sub.getMethod("getUniqueId")); assertNotNull(sub.getMethod("logicalPose"));
            assertNotNull(client.getMethod("renderPose",float.class));
            for(var name:new String[]{"position","rotationPoint","orientation","scale"})assertNotNull(poseApi.getMethod(name));
            var pose=Class.forName("dev.ryanhcode.sable.companion.math.Pose3d",true,loader);
            var constructor=pose.getConstructor(Vector3d.class,Quaterniond.class,Vector3d.class,Vector3d.class);
            Vec3 position=new Vec3(-512.1,94.7,844.2),pivot=new Vec3(160012.5,64,160012.5);
            for(int i=0;i<12;i++) {
                var rotation=new Quaterniond().rotationXYZ(i*.117,i*-.21,i*.29);
                var real=constructor.newInstance(new Vector3d(position.x,position.y,position.z),rotation,
                        new Vector3d(pivot.x,pivot.y,pivot.z),new Vector3d(1,1,1));
                var ours=new HomeShipSpace.Frame(UUID.randomUUID(),position,pivot,rotation);
                for(var point:new Vec3[]{pivot,pivot.add(1.2,2.8,-.7),pivot.add(-5,1.2,3.7)}) {
                    Vec3 world=(Vec3)poseApi.getMethod("transformPosition",Vec3.class).invoke(real,point);
                    HomeShipSpaceTest.near(world,ours.toWorld(point));
                    HomeShipSpaceTest.near((Vec3)poseApi.getMethod("transformPositionInverse",Vec3.class).invoke(real,world),ours.toStorage(world));
                    Vec3 dir=new Vec3(.2,.7,-.5).normalize();
                    HomeShipSpaceTest.near((Vec3)poseApi.getMethod("transformNormalInverse",Vec3.class).invoke(real,dir),ours.directionToStorage(dir));
                }
            }
            var box=Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d",true,loader);
            var bounds=box.getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
                    .newInstance(-3.,-2.,-1.,7.,5.,9.);
            assertEquals(-3.,(double)boxApi.getMethod("minX").invoke(bounds));
            assertEquals(9.,(double)boxApi.getMethod("maxZ").invoke(bounds));
        }
    }
}
