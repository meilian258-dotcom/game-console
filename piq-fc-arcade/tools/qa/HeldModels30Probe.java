package cn.piq.sfchome.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.*;

/** Executes supplied production methods and real MC transforms, without an MC instance/world/GPU. */
public final class HeldModels30Probe {
    private static int assertions;
    private static final List<String> failures=new ArrayList<>();
    private static final Map<String,Object> measurements=new LinkedHashMap<>();
    private static void check(boolean yes,String why){assertions++;if(!yes&&failures.size()<100)failures.add(why);}
    private static void close(double a,double b,double epsilon,String why){check(java.lang.Math.abs(a-b)<=epsilon,why+": "+a+" / "+b);}
    private static void origin(Class<?> type,Path jar)throws Exception{check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar.toRealPath()),"origin "+type.getName());}
    private static JsonObject json(ZipFile z,String entry)throws Exception{return JsonParser.parseString(new String(z.getInputStream(z.getEntry(entry)).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static Vector3f vec(JsonArray a){return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private static List<Vector3f> vertices(JsonObject model){
        var result=new ArrayList<Vector3f>();
        for(var element:model.getAsJsonArray("elements")){
            var e=element.getAsJsonObject();var a=vec(e.getAsJsonArray("from"));var b=vec(e.getAsJsonArray("to"));
            for(int i=0;i<8;i++){
                var v=new Vector3f((i&1)==0?a.x:b.x,(i&2)==0?a.y:b.y,(i&4)==0?a.z:b.z);
                if(e.has("rotation")){
                    var r=e.getAsJsonObject("rotation");check(!r.has("rescale")||!r.get("rescale").getAsBoolean(),"no unsupported element rescale");
                    var o=vec(r.getAsJsonArray("origin"));float angle=(float)java.lang.Math.toRadians(r.get("angle").getAsFloat());
                    Quaternionf q=switch(r.get("axis").getAsString()){case "x"->new Quaternionf().rotationX(angle);case "y"->new Quaternionf().rotationY(angle);case "z"->new Quaternionf().rotationZ(angle);default->throw new AssertionError("axis");};
                    v.sub(o).rotate(q).add(o);
                }
                result.add(v.mul(1f/16));
            }
        }
        return result;
    }
    private static float[] bounds(List<Vector3f> points,Matrix4f matrix){
        var lo=new Vector3f(Float.POSITIVE_INFINITY);var hi=new Vector3f(Float.NEGATIVE_INFINITY);
        for(var p:points){var v=matrix.transformPosition(new Vector3f(p));check(Float.isFinite(v.x)&&Float.isFinite(v.y)&&Float.isFinite(v.z),"finite transformed vertex");lo.min(v);hi.max(v);}
        return new float[]{lo.x,lo.y,lo.z,hi.x,hi.y,hi.z};
    }
    private static void items(ZipFile fc)throws Exception{
        for(String name:List.of("zapper_stand_cable","zapper_stand")){
            var wrapper=json(fc,"assets/piq_fc_arcade/models/item/"+name+".json");
            var model=name.equals("zapper_stand")?json(fc,"assets/piq_fc_arcade/models/block/zapper_stand/stand.json"):wrapper;
            var points=vertices(model);var display=wrapper.getAsJsonObject("display");
            for(String context:List.of("firstperson","thirdperson","gui","ground","fixed")){
                boolean handed=context.endsWith("person");float[][] box=new float[2][];
                for(int hand=0;hand<(handed?2:1);hand++){
                    var input=display.getAsJsonObject(handed?context+(hand==0?"_righthand":"_lefthand"):context);
                    var transform=new ItemTransform.Deserializer().deserialize(input,ItemTransform.class,null);
                    check(transform.scale.distance(vec(input.getAsJsonArray("scale")))<1e-6,"scale not clamped "+name);
                    check(transform.translation.distance(vec(input.getAsJsonArray("translation")).mul(1f/16))<1e-6,"translation not clamped "+name);
                    var pose=new PoseStack();transform.apply(hand!=0,pose);pose.translate(-.5,-.5,-.5);box[hand]=bounds(points,pose.last().pose());
                    var b=box[hand];float longest=0;
                    float targetY=context.equals("firstperson")||context.equals("ground")?2f/16:context.equals("thirdperson")?3f/16:0;
                    for(int axis=0;axis<3;axis++){float size=b[axis+3]-b[axis];check(size>0&&size<1.25,"finite visible item size "+name+context);longest=java.lang.Math.max(longest,size);check(java.lang.Math.abs((b[axis]+b[axis+3])*.5-(axis==1?targetY:0))<.12,"hand/item centered "+name+context+" hand="+hand+" axis="+axis+" bounds="+Arrays.toString(b));}
                    check(longest>.08,"not microscopic "+name+context);
                    if(context.equals("gui"))for(int i:new int[]{0,1,3,4})check(java.lang.Math.abs(b[i])<.475,"GUI fully visible");
                    measurements.put(name+"/"+context+"/"+hand,b);
                }
                if(handed){close(box[0][0],-box[1][3],.12,"mirrored geometry x-min");close(box[0][3],-box[1][0],.12,"mirrored geometry x-max");for(int i:new int[]{1,2,4,5})close(box[0][i],box[1][i],.12,"mirror geometry y/z");}
            }
        }
    }
    private static void sfc(ZipFile fc,ZipFile sfc,Path fcPath,Path sfcPath)throws Exception{
        origin(SfcControllerPose.class,sfcPath);origin(SfcControllerPoseLayout.class,sfcPath);origin(SfcHardwareMeshData.class,sfcPath);
        var mesh=SfcHardwareMeshData.read(new InputStreamReader(sfc.getInputStream(sfc.getEntry("assets/piq_sfc_home/meshes/sfc_hardware.json")),StandardCharsets.UTF_8));
        var points=new ArrayList<Vector3f>();for(var part:mesh.get("controller"))for(int i=0;i<part.vertices().length;i+=8)points.add(new Vector3f(part.vertices()[i],part.vertices()[i+1],part.vertices()[i+2]));
        check(points.size()>1000,"actual whole supplied controller mesh");
        var fcDisplay=json(fc,"assets/piq_fc_arcade/models/item/fc_controller.json").getAsJsonObject("display");
        for(boolean left:new boolean[]{false,true}){
            var actual=new PoseStack();SfcControllerPose.thirdTransform(actual,left);
            var reference=new PoseStack();new ItemTransform.Deserializer().deserialize(fcDisplay.getAsJsonObject(left?"thirdperson_lefthand":"thirdperson_righthand"),ItemTransform.class,null).apply(left,reference);
            reference.mulPose(Axis.XP.rotationDegrees(90));reference.mulPose(Axis.YP.rotationDegrees(180));
            float[] a=new float[16],b=new float[16];actual.last().pose().get(a);reference.last().pose().get(b);for(int i=0;i<16;i++)close(a[i],b[i],2e-6,"real SFC transform equals existing FC wrist rig + fixed axes");
            actual.translate(-.5,-.5,-.5);var bound=bounds(points,actual.last().pose());measurements.put("sfc/third/"+left,bound);
            for(int axis=0;axis<3;axis++)check(bound[axis+3]-bound[axis]>.015&&bound[axis+3]-bound[axis]<.9,"SFC actual third visible bounded size");
        }
        for(int equip=-1;equip<=11;equip++)for(int swing=-1;swing<=11;swing++){
            var rig=SfcControllerPoseLayout.rig(equip/10.,swing/10.);check(Double.isFinite(rig.y())&&Double.isFinite(rig.z())&&Double.isFinite(rig.pitch()),"finite first rig");
            check(rig.pitch()>=-60&&rig.pitch()<=-56&&rig.y()>=-1.071&&rig.z()>=-1.491&&rig.z()<=-1.46,"bounded equip/swing");
        }
        for(int equip=0;equip<=4;equip++)for(int swing=0;swing<=4;swing++){
            var rig=SfcControllerPoseLayout.rig(equip/4.,swing/4.);var pose=new PoseStack();pose.translate(0,rig.y(),rig.z());pose.mulPose(Axis.XP.rotationDegrees((float)rig.pitch()));pose.mulPose(Axis.XP.rotationDegrees(90));pose.mulPose(Axis.YP.rotationDegrees(180));pose.scale(.9f,.9f,.9f);pose.translate(-.5,-.5,-.5);
            var b=bounds(points,pose.last().pose());check(b[5]<-.25,"first whole controller remains in front of camera");for(int axis=0;axis<3;axis++)check(b[axis+3]-b[axis]<1.2,"first whole controller finite size");
            if(equip==0&&swing==0)measurements.put("sfc/first/idle",b);
        }
        Class<?> wrapper=Class.forName("cn.piq.sfchome.client.SfcHardwareItems$ItemModel");origin(wrapper,sfcPath);Constructor<?> constructor=wrapper.getDeclaredConstructor(BakedModel.class,boolean.class);constructor.setAccessible(true);
        int[] delegates={0};BakedModel original=(BakedModel)Proxy.newProxyInstance(HeldModels30Probe.class.getClassLoader(),new Class<?>[]{BakedModel.class},(proxy,called,arguments)->{if(called.getName().equals("applyTransform")){delegates[0]++;((PoseStack)arguments[1]).translate(2,3,4);return proxy;}throw new AssertionError("Unexpected GPU/model method "+called);});
        for(boolean controller:new boolean[]{false,true})for(ItemDisplayContext context:ItemDisplayContext.values())for(boolean leftHand:new boolean[]{false,true}){
            var object=(BakedModel)constructor.newInstance(original,controller);var pose=new PoseStack();var expected=new PoseStack();int before=delegates[0];
            boolean third=controller&&(context==ItemDisplayContext.THIRD_PERSON_LEFT_HAND||context==ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
            check(object.applyTransform(context,pose,leftHand)==object,"wrapper identity retained");
            if(third)SfcControllerPose.thirdTransform(expected,leftHand);else expected.translate(2,3,4);
            check(delegates[0]-before==(third?0:1),"only third controller bypasses original; other item/context delegated once");
            float[] a=new float[16],b=new float[16];pose.last().pose().get(a);expected.last().pose().get(b);for(int i=0;i<16;i++)close(a[i],b[i],1e-6,"actual wrapper matrix");
        }
        var right=SfcControllerPoseLayout.arm(true);var left=SfcControllerPoseLayout.arm(false);
        close(right.x(),-left.x(),1e-9,"first wrist x mirror");close(right.y(),left.y(),1e-9,"first wrist y");close(right.z(),left.z(),1e-9,"first wrist z");close(right.roll(),-left.roll(),1e-9,"first roll mirror");
        for(int flags=0;flags<128;flags++){boolean expected=(flags&1)!=0&&(flags&2)!=0&&(flags&4)!=0&&(flags&120)==0;check(SfcControllerPoseLayout.eligible((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0,(flags&32)!=0,(flags&64)!=0)==expected,"first support hand gate");}
        Field field=SfcControllerPose.class.getDeclaredField("FIRST_ARMS");field.setAccessible(true);@SuppressWarnings("unchecked") ThreadLocal<Boolean> local=(ThreadLocal<Boolean>)field.get(null);
        check(!local.get(),"first guard initially clear");local.set(true);try{check(SfcControllerPose.armPose(null,null,null)==null,"first guard prevents new FC enum during skin arm render");boolean[] observed={true};Thread isolated=new Thread(()->observed[0]=local.get());isolated.start();isolated.join();check(!observed[0],"first guard thread-local");}finally{local.set(false);}
        check(SfcControllerPose.armPose(null,null,null)==null,"non player never gains arm pose");
        Class<?> pose=Class.forName("cn.piq.fcarcade.client.ControllerPose");origin(pose,fcPath);Method apply=pose.getDeclaredMethod("applyArms",HumanoidModel.class,HumanoidArm.class,boolean.class);apply.setAccessible(true);
        Map<String,ModelPart> parts=new HashMap<>();for(String name:List.of("head","hat","body","right_arm","left_arm","right_leg","left_leg"))parts.put(name,new ModelPart(List.of(),Map.of()));
        var model=new HumanoidModel<>(new ModelPart(List.of(),parts));
        for(boolean two:new boolean[]{false,true})for(HumanoidArm hand:HumanoidArm.values()){
            model.leftArm.xRot=model.rightArm.xRot=.21f;model.leftArm.yRot=model.rightArm.yRot=.37f;model.leftArm.zRot=model.rightArm.zRot=.19f;
            apply.invoke(null,model,hand,two);var holding=hand==HumanoidArm.RIGHT?model.rightArm:model.leftArm;var other=hand==HumanoidArm.RIGHT?model.leftArm:model.rightArm;
            check(Float.isFinite(holding.xRot)&&java.lang.Math.abs(holding.xRot)<1.6&&java.lang.Math.abs(holding.yRot)<.8&&java.lang.Math.abs(holding.zRot)<.9,"registered FC pose finite bounded");
            if(!two){close(other.xRot,.21f,1e-7,"nonempty other arm x untouched");close(other.yRot,.37f,1e-7,"nonempty other arm y untouched");close(other.zRot,.19f,1e-7,"nonempty other arm z untouched");}
            else{close(model.leftArm.xRot,model.rightArm.xRot,1e-7,"two hand pitch");close(model.leftArm.zRot,-model.rightArm.zRot,1e-7,"two hand roll mirror");}
        }
    }
    public static void main(String[] args)throws Exception{
        check(args.length==3,"FC/SFC/MC input jars");Path fc=Path.of(args[0]),sfc=Path.of(args[1]),mc=Path.of(args[2]);origin(ItemTransform.class,mc);origin(PoseStack.class,mc);
        try(var f=new ZipFile(fc.toFile());var s=new ZipFile(sfc.toFile())){items(f);sfc(f,s,fc,sfc);}
        var out=new LinkedHashMap<String,Object>();out.put("ok",failures.isEmpty());out.put("assertions",assertions);out.put("failures",failures);out.put("production_origin","supplied-final-jar-only");out.put("production_compiled",false);out.put("actual_minecraft_itemtransform",true);out.put("actual_pose_stack",true);out.put("actual_sfc_third_transform",true);out.put("actual_fc_apply_arms",true);out.put("minecraft_instance_world_or_gpu_started",false);out.put("measurements",measurements);System.out.println(new Gson().toJson(out));
    }
}
