package cn.piq.fcarcade.client;

import static org.junit.jupiter.api.Assertions.*;
import cn.piq.fcarcade.compat.HomeShipSpace;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** Executes CPU matrix math, not a Minecraft/GPU/flight or shader-pack integration test. */
class HomeScreenRenderPoseTest {
    @Test void groundPoseIsExactlyTheOriginalAnchorMinusCameraTranslation() {
        for (var anchor : new BlockPos[]{new BlockPos(12,70,-35), new BlockPos(-16000002,94,15999801)}) {
            var camera = new Vec3(anchor.getX()+1.4, anchor.getY()+.7, anchor.getZ()+3.2);
            var expected = viewPose();
            expected.translate(anchor.getX()-camera.x,anchor.getY()-camera.y,anchor.getZ()-camera.z);
            var actual = viewPose();
            assertTrue(HomeScreenRenderPose.apply(actual,HomeShipSpace.GROUND,anchor,camera));
            assertEquals(expected.last().pose(),actual.last().pose());
            assertEquals(expected.last().normal(),actual.last().normal());
        }
    }

    @Test void farPlotScreensFollowTranslationPitchYawRollAndTheExistingViewPose() {
        for (int i=0;i<24;i++) {
            var anchor = new BlockPos(16000002,80,-15999991);
            var rotation = new Quaterniond().rotationXYZ(i*.17,-i*.29,i*.11);
            var frame = new HomeShipSpace.Frame(UUID.randomUUID(),new Vec3(-120+i*13.2,71+i*.43,340-i*2.8),
                    new Vec3(16000000.5,64,-16000000.5),rotation);
            var worldOrigin = frame.toWorld(Vec3.atLowerCornerOf(anchor));
            var camera = worldOrigin.add(1.3,1.7,4.2);
            assertTrue(Vec3.atLowerCornerOf(anchor).subtract(camera).lengthSqr()>1e14,
                    "The old global screen translation was still in the distant ship plot");
            var poses = viewPose(); var parent = new Matrix4f(poses.last().pose());
            assertTrue(HomeScreenRenderPose.apply(poses,frame,anchor,camera));
            for (var local : new Vec3[]{Vec3.ZERO,new Vec3(.1,.2,.3),new Vec3(-1.7,3.2,.001),new Vec3(2.4,.3,1.2)}) {
                var world = frame.toWorld(Vec3.atLowerCornerOf(anchor).add(local)).subtract(camera);
                var expected = parent.transformPosition(new Vector3f((float)world.x,(float)world.y,(float)world.z));
                var actual = poses.last().pose().transformPosition(new Vector3f((float)local.x,(float)local.y,(float)local.z));
                near(expected,actual,2e-5f);
            }
        }
    }

    @Test void normalGetsTheSameRigidRotationWithoutTranslationOrScale() {
        var rotation = new Quaterniond().rotationXYZ(.72,-1.3,.43);
        var frame = new HomeShipSpace.Frame(UUID.randomUUID(),new Vec3(32,73,-12),new Vec3(16000000,64,16000000),rotation);
        var poses = viewPose(); var parent = new Matrix3f(poses.last().normal());
        assertTrue(HomeScreenRenderPose.apply(poses,frame,new BlockPos(16000001,65,16000002),new Vec3(34,74,-10)));
        for (var normal : new Vector3f[]{new Vector3f(1,0,0),new Vector3f(0,1,0),new Vector3f(0,0,-1)}) {
            var rotated = rotation.transform(new Vector3d(normal));
            var expected = parent.transform(new Vector3f((float)rotated.x,(float)rotated.y,(float)rotated.z));
            near(expected,poses.last().normal().transform(new Vector3f(normal)),1e-6f);
        }
    }

    @Test void missingPoseAndInvalidCameraFailBeforeTouchingTheStack() {
        var poses=viewPose();var before=new Matrix4f(poses.last().pose());var normal=new Matrix3f(poses.last().normal());
        assertFalse(HomeScreenRenderPose.apply(poses,null,BlockPos.ZERO,Vec3.ZERO));
        assertFalse(HomeScreenRenderPose.apply(poses,HomeShipSpace.GROUND,null,Vec3.ZERO));
        assertFalse(HomeScreenRenderPose.apply(poses,HomeShipSpace.GROUND,BlockPos.ZERO,new Vec3(Double.NaN,0,0)));
        assertEquals(before,poses.last().pose());assertEquals(normal,poses.last().normal());
    }

    @Test void callerPushPopKeepsTheNextScreenAndBlockEntityUnchanged() {
        var poses=viewPose();var before=new Matrix4f(poses.last().pose());var normal=new Matrix3f(poses.last().normal());
        poses.pushPose();
        assertTrue(HomeScreenRenderPose.apply(poses,new HomeShipSpace.Frame(UUID.randomUUID(),new Vec3(10,70,-80),
                new Vec3(16000000,64,16000000),new Quaterniond().rotationXYZ(.2,.8,.9)),
                new BlockPos(16000002,65,16000003),new Vec3(11,74,-80)));
        poses.popPose();
        assertEquals(before,poses.last().pose());assertEquals(normal,poses.last().normal());
    }

    @Test void everyFcGlobalScreenUsesTheHelperAndBerAndExternalPathsDoNot() throws Exception {
        for(var name:new String[]{"ArcadeBlockScreenRenderer","PrivateHomeClient","FcHomeWatchDisplay"}) {
            var source=source(name);
            assertTrue(source.contains("HomeScreenRenderPose.apply(event,"),name);
            assertTrue(source.contains("stack.pushPose()"),name);
            assertTrue(source.contains("stack.popPose()"),name);
        }
        for(var name:new String[]{"HomeHardwareRenderer","HomeApplianceClient","HomeVideoDisplay","ControllerCableRenderer"})
            assertFalse(source(name).contains("HomeScreenRenderPose.apply("),name);
        var helper=source("HomeScreenRenderPose");
        assertTrue(helper.contains("instanceof HomeConsoleBlockEntity console"));
        assertTrue(helper.contains("!(console.getBlockState().getBlock() instanceof SuborConsoleBlock)"));
        assertTrue(helper.contains("level.hasChunkAt(tv.consolePos())"));
        assertTrue(helper.contains("event.getPartialTick().getGameTimeDeltaPartialTick(true)"));
        assertTrue(helper.contains("HomeHardware.connectedConsole(level, anchor) != console"));
        assertTrue(helper.contains("frame == null"));
        assertFalse(helper.contains("getChunk("));
        assertFalse(helper.contains("DynamicTexture"));
        assertFalse(helper.contains("sendToServer"));
    }

    private static PoseStack viewPose() {
        var poses = new PoseStack();
        poses.mulPose(new Quaternionf().rotationXYZ(.14f,-.37f,.09f));
        return poses;
    }
    private static void near(Vector3f expected,Vector3f actual,float tolerance) {
        assertEquals(expected.x,actual.x,tolerance);assertEquals(expected.y,actual.y,tolerance);assertEquals(expected.z,actual.z,tolerance);
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+name+".java"));
    }
}
