import cn.piq.fcarcade.layout.ZapperPoseLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Vector3f;
import java.util.*;
import java.nio.file.Path;

/** Real PoseStack float matrix against pure production transform, without a game/window. */
public final class ZapperPoseProbe {
    public static void main(String[] args)throws Exception {
        int checks=0;var poses=new LinkedHashMap<String,Object>();
        if(args.length>1)throw new IllegalArgumentException("Optional actual final JAR path");
        if(args.length==1)for(String name:new String[]{"cn.piq.fcarcade.layout.ZapperPoseLayout","cn.piq.fcarcade.layout.ZapperAimGeometry","cn.piq.fcarcade.client.zapper.ZapperInputState"}) {
            var actual=Path.of(Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if(!actual.equals(Path.of(args[0]).toRealPath()))throw new AssertionError("Production class not from final JAR: "+name);checks++;
        }
        for(var view:ZapperPoseLayout.View.values()) {
            var p=ZapperPoseLayout.item(view);var stack=new PoseStack();
            stack.translate(p.x(),p.y(),p.z());stack.mulPose(Axis.YP.rotationDegrees((float)p.yaw()));
            stack.mulPose(Axis.XP.rotationDegrees((float)p.pitch()));stack.mulPose(Axis.ZP.rotationDegrees((float)p.roll()));
            stack.scale((float)p.scale(),(float)p.scale(),(float)p.scale());stack.translate(-p.origin().x()/16,-p.origin().y()/16,-p.origin().z()/16);
            for(int x=2;x<=13;x++)for(int y=0;y<=8;y++)for(int z=7;z<=10;z++) {
                var expected=ZapperPoseLayout.itemPoint(new Point(x,y,z),p);
                var actual=stack.last().pose().transformPosition(new Vector3f(x/16F,y/16F,z/16F));
                if(Math.abs(expected.x()-actual.x)>2e-6||Math.abs(expected.y()-actual.y)>2e-6||Math.abs(expected.z()-actual.z)>2e-6)throw new AssertionError(view+" matrix differs");checks++;
            }
            poses.put(view.name(),p);
        }
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"actual_minecraft_pose_stack",true,
                "minecraft_started",false,"poses",poses,"first_right",ZapperPoseLayout.first(true,0),
                "first_left",ZapperPoseLayout.first(false,0),"trigger_pivot",ZapperPoseLayout.TRIGGER_PIVOT,"trigger_degrees",ZapperPoseLayout.TRIGGER_DEGREES)));
    }
}
