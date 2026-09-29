package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.nio.file.Path;
import net.minecraft.client.Camera;
import org.joml.Vector3f;

/** Real Camera quaternion / PoseStack verification, without constructing a Minecraft instance or world. */
public final class ControllerCableVisualProbe {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok)throw new AssertionError("Cable visual " + checks); }
    private static final class ProbeCamera extends Camera {
        void angles(float yaw,float pitch,float roll){setRotation(yaw,pitch,roll);}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("production location, actual MC jar");
        Path expected=Path.of(args[0]).toRealPath(), mc=Path.of(args[1]).toRealPath();
        for(Class<?> type:new Class<?>[]{ControllerCableGeometry.class,ControllerCableGeometry.Style.class,ControllerPoseLayout.class,RocketArcadeGeometry.class})
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected));
        for(Class<?> type:new Class<?>[]{Camera.class,PoseStack.class})
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(mc));
        for(var style:ControllerCableGeometry.Style.values())for(int port=0;port<2;port++)for(int turn=0;turn<4;turn++) {
            var n=ControllerCableGeometry.socket(style,port,0);var p=ControllerCableGeometry.socket(style,port,turn);
            var pose=new PoseStack();pose.translate(.5,0,.5);pose.mulPose(Axis.YP.rotationDegrees(-90*turn));pose.translate(-.5,0,-.5);
            var actual=pose.last().pose().transformPosition(new Vector3f((float)n.x(),(float)n.y(),(float)n.z()));
            check(Math.abs(actual.x-p.x())<1e-6&&Math.abs(actual.y-p.y())<1e-6&&Math.abs(actual.z-p.z())<1e-6);
        }
        var camera=new ProbeCamera();
        for(int yaw=0;yaw<360;yaw+=15)for(int pitch=-80;pitch<=80;pitch+=20)for(boolean right:new boolean[]{false,true})for(boolean two:new boolean[]{false,true})for(int swing=0;swing<=10;swing++) {
            var rig=ControllerPoseLayout.first(right,two,0,swing/10d);
            var p=ControllerCableGeometry.firstGrip(rig.x(),rig.y(),rig.z(),rig.pitch(),rig.yaw());
            var real=new PoseStack();real.translate(rig.x(),rig.y(),rig.z());real.mulPose(Axis.XP.rotationDegrees((float)rig.pitch()));real.mulPose(Axis.YP.rotationDegrees((float)rig.yaw()));
            var transformed=real.last().pose().transformPosition(new Vector3f(0,.14f,0));
            check(Math.abs(transformed.x-p.x())<1e-6&&Math.abs(transformed.y-p.y())<1e-6&&Math.abs(transformed.z-p.z())<1e-6);
            camera.angles(yaw,pitch,0);
            var world=camera.rotation().transform(new Vector3f((float)p.x(),(float)p.y(),(float)p.z()));
            check(world.dot(camera.getLookVector())>1.45f);
            check(world.length()<2.1f);
            var back=new Vector3f(world);new org.joml.Quaternionf(camera.rotation()).conjugate().transform(back);
            check(Math.abs(back.x-p.x())<2e-6&&Math.abs(back.y-p.y())<2e-6&&Math.abs(back.z-p.z())<2e-6);
            var start=ControllerCableGeometry.socket(ControllerCableGeometry.Style.FAMICOM,0,0);
            var path=ControllerCableGeometry.cable(start,new RocketArcadeGeometry.Point(world.x,world.y+1.62,world.z));
            check(!path.isEmpty()&&path.size()<=65);
        }
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_camera_and_pose_stack\":true,\"minecraft_instance_or_world_created\":false}");
    }
}
