package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Actual vanilla transform order, public-hook wiring and pure state policy; not a live-game render test. */
class ControllerPoseLayoutTest {
    record V(double x, double y, double z) {
        V add(V b) { return new V(x+b.x, y+b.y, z+b.z); }
        V scale(double s) { return new V(x*s,y*s,z*s); }
        double dot(V b) { return x*b.x+y*b.y+z*b.z; }
        V unit() { return scale(1/Math.sqrt(dot(this))); }
    }
    record Display(V rotation, V translation, V scale) {}
    static V rx(V v, double degrees) { double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);return new V(v.x,c*v.y-s*v.z,s*v.y+c*v.z); }
    static V ry(V v, double degrees) { double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);return new V(c*v.x+s*v.z,v.y,-s*v.x+c*v.z); }
    static V rz(V v, double degrees) { double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);return new V(c*v.x-s*v.y,s*v.x+c*v.y,v.z); }
    static V display(V centered, Display display, boolean left) {
        V r=display.rotation;
        V p=new V(centered.x*display.scale.x,centered.y*display.scale.y,centered.z*display.scale.z);
        p=rx(ry(rz(p,left?-r.z:r.z),left?-r.y:r.y),r.x);
        return p.add(new V((left?-1:1)*display.translation.x/16,display.translation.y/16,display.translation.z/16));
    }
    static V first(V rawModelUnits, int port, boolean right, boolean two, double swing) throws Exception {
        // ItemRenderer T(-.5), BEWLR T(.5)*Ry(portYaw)*T(-.5), model vertices /16.
        V canonical=ry(rawModelUnits.scale(1/16.0).add(new V(-.5,-.5,-.5)),port==0?-90:90);
        V p=display(canonical,display(right?"firstperson_righthand":"firstperson_lefthand"),!right);
        var hook=ControllerPoseLayout.first(right,two,0,swing);
        return rx(ry(p,hook.yaw()),hook.pitch()).add(new V(hook.x(),hook.y(),hook.z()));
    }
    static V third(V canonical, boolean right, boolean slim) throws Exception {
        V p=display(canonical,display(right?"thirdperson_righthand":"thirdperson_lefthand"),!right);
        // ItemInHandLayer translateToHand -> Rx(-90) -> Ry(180) -> T(+/-1/16,.125,-.625).
        p=p.add(new V((right?1:-1)/16.0,.125,-.625));
        p=rx(ry(p,180),-90);
        p=rx(p,Math.toDegrees(ControllerPoseLayout.THIRD_ARM_PITCH));
        p=rz(p,Math.toDegrees(ControllerPoseLayout.THIRD_ARM_INWARD_ROLL)*(right?-1:1));
        return p.add(new V((right?-1:1)*(slim?4.5:5)/16.0,2/16.0,0));
    }
    static boolean inside(V point, double fov, double aspect) {
        double extent=-point.z*Math.tan(Math.toRadians(fov/2));
        return point.z<-.05 && Math.abs(point.y)<extent && Math.abs(point.x)<extent*aspect;
    }
    static double screenY(V point, double fov) {
        return .5-point.y/(-point.z*Math.tan(Math.toRadians(fov/2)))*.5;
    }
    static V armPoint(V cubePoint, boolean right, boolean slim, boolean old) {
        var arm=ControllerPoseLayout.firstArm(right);
        // setupAnim resets both default/slim shoulder pivots to (+/-5,2,0).
        V p=cubePoint.add(new V(right?-5:5,2,0)).scale(1/16.0).scale(arm.scale());
        p=rz(rx(p,arm.pitch()),arm.roll()).add(new V(arm.x(),arm.y(),arm.z()));
        var common=ControllerPoseLayout.first(right,true,0,0);
        return rx(p,old?-16:common.pitch()).add(old?new V(0,-.22,-.90):new V(common.x(),common.y(),common.z()));
    }
    static Display display(String name) throws Exception {
        String json=Files.readString(Path.of("src/main/resources/assets/piq_fc_arcade/models/item/fc_controller.json"));
        var object=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\{([^}]+)}").matcher(json);
        assertTrue(object.find(),name);
        return new Display(vector(object.group(1),"rotation",new V(0,0,0)),vector(object.group(1),"translation",new V(0,0,0)),vector(object.group(1),"scale",new V(1,1,1)));
    }
    static V vector(String json,String name,V fallback) {
        var match=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\[([^]]+)]").matcher(json);
        if(!match.find())return fallback;
        var parts=match.group(1).split(",");return new V(Double.parseDouble(parts[0]),Double.parseDouble(parts[1]),Double.parseDouble(parts[2]));
    }
    static String source(String name) throws Exception { return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client",name+".java")); }

    @Test void noOtherItemOrUnsafePlayerStateReceivesOurArmPose() {
        assertTrue(ControllerPoseLayout.eligible(true,true,true,false,false,false));
        assertFalse(ControllerPoseLayout.eligible(false,true,true,false,false,false));
        assertFalse(ControllerPoseLayout.eligible(true,false,true,false,false,false));
        assertFalse(ControllerPoseLayout.eligible(true,true,false,false,false,false));
        assertFalse(ControllerPoseLayout.eligible(true,true,true,true,false,false));
        assertFalse(ControllerPoseLayout.eligible(true,true,true,false,true,false));
        assertFalse(ControllerPoseLayout.eligible(true,true,true,false,false,true));
        assertTrue(ControllerPoseLayout.twoHands(true,true));
        assertFalse(ControllerPoseLayout.twoHands(true,false));
        assertFalse(ControllerPoseLayout.twoHands(false,true));
    }
    @Test void soloControllersAreCenteredForEitherMainArmAndOtherObjectsKeepTheirSide() {
        var right=ControllerPoseLayout.first(true,true,0,0); var left=ControllerPoseLayout.first(false,true,0,0);
        assertEquals(right.x(),left.x(),0); assertEquals(right.y(),left.y(),0); assertEquals(right.z(),left.z(),0);
        assertEquals(right.pitch(),left.pitch(),0); assertEquals(right.yaw(),left.yaw(),0);
        assertEquals(.34,ControllerPoseLayout.first(true,false,0,0).x());
        assertEquals(-.34,ControllerPoseLayout.first(false,false,0,0).x());
    }
    @Test void enlargedHandsKeepTheDistalGripFixedAcrossUserSizes() {
        for(boolean right:new boolean[]{true,false})for(double size:new double[]{1,1.15,1.18,1.20,1.30}) {
            var pose=ControllerPoseLayout.firstArm(right,size);var old=ControllerPoseLayout.firstArm(right,1);
            V anchor=new V((right?-1:1)*6/16.0,11/16.0,0);
            V next=rz(rx(anchor.scale(pose.scale()),pose.pitch()),pose.roll()).add(new V(pose.x(),pose.y(),pose.z()));
            V previous=rz(rx(anchor.scale(old.scale()),old.pitch()),old.roll()).add(new V(old.x(),old.y(),old.z()));
            assertEquals(previous.x,next.x,1e-12);assertEquals(previous.y,next.y,1e-12);assertEquals(previous.z,next.z,1e-12);
            assertEquals(.82*size,pose.scale(),1e-12);
        }
    }
    @Test void equipAndSwingAreBoundedAndReturnToTheIdlePose() {
        assertEquals(ControllerPoseLayout.first(true,true,0,0),ControllerPoseLayout.first(true,true,-5,-3));
        assertEquals(ControllerPoseLayout.first(true,true,1,1),ControllerPoseLayout.first(true,true,5,3));
        for(double swing:new double[]{0,.05,.25,.5,.9,1}) {
            var pose=ControllerPoseLayout.first(true,true,0,swing);
            assertTrue(pose.y()>=-.621&&pose.y()<=-.601);
            assertTrue(pose.z()>=-1.491&&pose.z()<=-1.459);
            assertTrue(pose.pitch()>=-60.001&&pose.pitch()<=-55.999);
        }
    }
    @Test void itemJsonKeepsTheFirstPersonCenterNeutralInsteadOfApplyingTheOldDownwardOffsetTwice() throws Exception {
        for(String context:List.of("firstperson_righthand","firstperson_lefthand")) {
            var value=display(context);
            assertEquals(new V(0,0,0),value.translation);
            assertEquals(new V(0,0,0),value.rotation);
            assertEquals(new V(.85,.85,.85),value.scale);
        }
    }
    @Test void completeP1AndP2ConservativeBoundsStayInsideTheActualHandPerspectiveIncludingWaterFov() throws Exception {
        for(int port=0;port<2;port++)for(boolean right:new boolean[]{true,false})for(boolean two:new boolean[]{true,false})
            for(double swing:new double[]{0,.3,.7,1})for(double fov:new double[]{60,70})for(double aspect:new double[]{4/3.0,16/9.0})
                for(double x:new double[]{6.85,9.15})for(double y:new double[]{5.15,10.85})for(double z:new double[]{1.55,14.45}) {
                    V point=first(new V(x,y,z),port,right,two,swing);
                    assertTrue(inside(point,fov,aspect),port+" "+right+" "+two+" "+fov+" "+point);
                }
    }
    @Test void commonIdleTranslationLowersTheRigWithoutChangingLocalGripOrThirdPerson() {
        var pose=ControllerPoseLayout.first(true,true,0,0);
        assertEquals(-.62,pose.y()); assertEquals(-1.46,pose.z()); assertEquals(-60,pose.pitch());
        assertEquals(-1.58,ControllerPoseLayout.first(true,false,0,0).z());
        assertEquals(-Math.PI/3,ControllerPoseLayout.THIRD_ARM_PITCH);
        assertEquals(Math.toRadians(38),ControllerPoseLayout.THIRD_ARM_INWARD_ROLL);
        for(boolean right:new boolean[]{true,false}) {
            var arm=ControllerPoseLayout.firstArm(right,1);
            V anchor=new V((right?-1:1)*6/16.0,11/16.0,0);
            V original=rz(rx(anchor.scale(.82),-38),right?24:-24).add(new V(right?.80:-.80,-.36,.38));
            V current=rz(rx(anchor.scale(arm.scale()),arm.pitch()),arm.roll()).add(new V(arm.x(),arm.y(),arm.z()));
            assertEquals(original.x,current.x,1e-12);assertEquals(original.y,current.y,1e-12);assertEquals(original.z,current.z,1e-12);
            assertEquals(30,arm.pitch()); assertEquals(right?60:-60,arm.roll()); assertEquals(.82,arm.scale());
        }
    }
    @Test void idleConservativeControllerBoxLeavesTheCenterClearAndWaterBottomMargin() throws Exception {
        for(int port=0;port<2;port++)for(boolean right:new boolean[]{true,false})for(boolean two:new boolean[]{true,false})
            for(double fov:new double[]{60,70}) {
                double top=Double.POSITIVE_INFINITY,bottom=Double.NEGATIVE_INFINITY;
                for(double x:new double[]{6.85,9.15})for(double y:new double[]{5.15,10.85})for(double z:new double[]{1.55,14.45}) {
                    double screen=screenY(first(new V(x,y,z),port,right,two,0),fov);
                    top=Math.min(top,screen);bottom=Math.max(bottom,screen);
                }
                assertTrue(top>=(two?.72:.70),port+" "+right+" "+two+" "+fov+" top="+top);
                assertTrue(bottom<.98,port+" "+right+" "+two+" "+fov+" bottom="+bottom);
                if(two&&fov==70) {
                    assertTrue(top<.74&&bottom>.88&&bottom<.90);
                    assertTrue(bottom-top>.15&&bottom-top<.17);
                }
            }
    }
    @Test void completeDefaultSlimArmAndSleeveUpperEdgesMoveDownWithTheSameCommonMatrix() {
        for(boolean right:new boolean[]{true,false})for(boolean slim:new boolean[]{true,false}) {
            double minX=right?(slim?-2:-3):-1,maxX=minX+(slim?3:4);
            double top=Double.POSITIVE_INFINITY,oldTop=Double.POSITIVE_INFINITY;
            // The sleeve is the conservative arm cube inflated by .25 model pixels.
            for(double x:new double[]{minX-.25,maxX+.25})for(double y:new double[]{-2.25,10.25})for(double z:new double[]{-2.25,2.25}) {
                V cube=new V(x,y,z),point=armPoint(cube,right,slim,false);
                assertTrue(point.z<-.05);
                top=Math.min(top,screenY(point,70));
                oldTop=Math.min(oldTop,screenY(armPoint(cube,right,slim,true),70));
            }
            assertTrue(top>=.72&&top<.82,"enlarged arm upper edge="+top);
            assertTrue(top-oldTop>.10,"shared whole-rig lowering="+(top-oldTop));
        }
    }
    @Test void oldVanillaDownwardPlacementClippedTheFaceButTheNewCombinedPipelineDoesNot() throws Exception {
        V oldTop=new V(.56,-.52-1/16.0+(10.85-8)/16*.7,-.72);
        V oldBottom=new V(.56,-.52-1/16.0+(5.15-8)/16*.7,-.72);
        assertTrue(inside(oldTop,70,16/9.0));
        assertFalse(inside(oldBottom,70,16/9.0));
        assertTrue(inside(first(new V(9.15,5.15,8),0,true,true,0),70,16/9.0));
    }
    @Test void bothPortButtonFacesTiltThirtyDegreesAboveHorizontalWithoutMirroring() throws Exception {
        for(int port=0;port<2;port++)for(boolean right:new boolean[]{true,false}) {
            V center=first(new V(8,8,8),port,right,true,0);
            V button=first(new V(port==0?9:7,8,8),port,right,true,0);
            V normal=button.add(center.scale(-1)).unit();
            assertEquals(Math.sqrt(3)/2,normal.y,1e-12);
            assertEquals(.5,normal.z,1e-12);
            // Oblique, not face-on: the top stays readable and the key travel
            // now has a visible vertical component in the hand perspective.
            double viewCosine=normal.dot(center.scale(-1).unit());
            assertTrue(viewCosine>.79&&viewCosine<.81);
        }
    }
    @Test void unchangedFcButtonTravelHasVisibleDepthInTheSlantedPerspective() throws Exception {
        for(int port=0;port<2;port++)for(boolean right:new boolean[]{true,false}) {
            // The actual A cap's top center, converted from canonical pixels to
            // each existing P1/P2 model orientation. Its travel remains .10.
            V up=port==0?new V(9.034,6.84856,3.68041):new V(6.966,6.84856,12.31959);
            V down=up.add(new V(port==0?-.10:.10,0,0));
            V a=first(up,port,right,true,0),b=first(down,port,right,true,0);
            double pixels=(screenY(b,70)-screenY(a,70))*1080;
            assertTrue(pixels>1.8&&pixels<2.1,"Actual unchanged key stroke="+pixels);
        }
    }
    @Test void firstPersonDefaultAndSlimActualHandCentersMeetTheControllerEnds() {
        for(boolean right:new boolean[]{true,false})for(boolean slim:new boolean[]{true,false}) {
            var pose=ControllerPoseLayout.firstArm(right);
            V hand=new V((right?-1:1)*(slim?5.5:6)/16.0,11/16.0,0).scale(pose.scale());
            hand=rz(rx(hand,pose.pitch()),pose.roll()).add(new V(pose.x(),pose.y(),pose.z()));
            double grip=(right?1:-1)*12.69/32*.85;
            assertEquals(grip,hand.x,.04);
            // Slim's half-pixel asymmetric arm shifts its center, not the
            // anchored grip, by less than half a model pixel after wrist tilt.
            assertEquals(-.08,hand.y,.5/16);
            assertEquals(.03,hand.z,.005);
        }
    }
    @Test void thirdPersonVanillaLayerAndJsonPutOneControllerBetweenBothHandsForDefaultAndSlim() throws Exception {
        for(boolean right:new boolean[]{true,false})for(boolean slim:new boolean[]{true,false}) {
            V center=third(new V(0,0,0),right,slim);
            assertEquals(slim?(right?1:-1)*.5/16:0,center.x,1e-9);
            assertTrue(center.y>.25&&center.y<.40);
            assertTrue(center.z<-.55&&center.z>-.65);
            V normal=third(new V(0,0,1),right,slim).add(center.scale(-1)).unit();
            V eyes=new V(0,-6/16.0,0).add(center.scale(-1)).unit();
            assertTrue(normal.dot(eyes)>.99);
            V a=third(new V(-12.69/32,0,0),right,slim),b=third(new V(12.69/32,0,0),right,slim);
            assertEquals(12.69/16*.6,Math.abs(a.x-b.x),1e-9);
            assertTrue(Math.min(a.x,b.x)<-.20&&Math.max(a.x,b.x)>.20);
            // Distal center of the actual default/slim player arm, independently transformed from its shoulder.
            for(boolean gripRight:new boolean[]{true,false}) {
                V wrist=new V((gripRight?-1:1)*(slim?.5:1),10,-2).scale(1/16.0);
                wrist=rz(rx(wrist,-60),gripRight?-38:38).add(new V((gripRight?-5:5)/16.0,2/16.0,0));
                V end=a.x*(gripRight?-1:1)>b.x*(gripRight?-1:1)?a:b;
                // Slim's asymmetric translateToHand adds half a model pixel; contact stays within one pixel,
                // smaller than the slim palm's 1.5-pixel half-width (default palm: two pixels).
                assertEquals(wrist.x,end.x,1/16.0);
                assertEquals(wrist.y,end.y,.020);
                assertEquals(wrist.z,end.z,.002);
            }
        }
    }
    @Test void thirdPersonMirrorsOnlyTheHandTranslationAndKeepsGuiGroundFixedUnchanged() throws Exception {
        assertEquals(display("thirdperson_righthand"),display("thirdperson_lefthand"));
        assertEquals(new V(-2.97557615,-2.01331410,-1.16238744),display("thirdperson_righthand").translation);
        assertEquals(new V(.6,.6,.6),display("thirdperson_righthand").scale);
        assertEquals(new V(12,0,0),display("gui").rotation);
        assertEquals(new V(0,2,0),display("ground").translation);
        assertEquals(new V(.9,.9,.9),display("fixed").scale);
    }
    @Test void customHooksAreScopedToControllerAndHonorAlreadyCancelledArmsAndOtherHeldObjects() throws Exception {
        String renderer=source("HomeHardwareRenderer"),pose=source("ControllerPose");
        assertTrue(renderer.contains("return ControllerPose.firstTransform(poses, player, arm, stack, equip, swing)"));
        assertTrue(renderer.contains("return ControllerPose.armPose(entity, hand, stack)"));
        assertTrue(pose.contains("if (!controller(stack)) return false"));
        assertTrue(pose.contains("return HomeControllerData.isController(stack) || EXTERNAL.contains(stack.getItem())"));
        assertTrue(pose.contains("EXTERNAL.add(java.util.Objects.requireNonNull(item))"));
        assertTrue(pose.contains("if (event.isCanceled()) return"));
        assertTrue(pose.contains("event.getItemStack().isEmpty() && player.getItemInHand(event.getHand()).isEmpty()"));
        assertTrue(pose.contains("renderer.renderRightHand(")); assertTrue(pose.contains("renderer.renderLeftHand("));
        assertTrue(pose.contains("finally { DRAWING_FIRST_ARMS.set(previous); poses.popPose(); }"));
        assertTrue(pose.contains("if (two) poseArm("));
        assertFalse(pose.contains("setAccessible(")); assertFalse(pose.toLowerCase().contains("tacz"));
        assertFalse(pose.contains("keyAttack")); assertFalse(pose.contains("options."));
    }
    @Test void enumRegistrationHasBothProperConstructorDescriptorsAndEarlyParametersHaveNoGameState() throws Exception {
        String json=Files.readString(Path.of("src/main/resources/META-INF/piq-fc-controller-enumextensions.json"));
        assertTrue(json.contains("PIQ_FC_ARCADE_CONTROLLER_TWO_HANDS"));
        assertTrue(json.contains("PIQ_FC_ARCADE_CONTROLLER_SINGLE_HAND"));
        assertEquals(3,json.split(Pattern.quote("(ZLnet/neoforged/neoforge/client/IArmPoseTransformer;)V"),-1).length-1);
        var entries=com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(3,entries.size());
        for(int i=0;i<3;i++) {
            var entry=entries.get(i).getAsJsonObject();
            assertEquals("net/minecraft/client/model/HumanoidModel$ArmPose",entry.get("enum").getAsString());
            assertEquals("(ZLnet/neoforged/neoforge/client/IArmPoseTransformer;)V",entry.get("constructor").getAsString());
            assertEquals(List.of("PIQ_FC_ARCADE_CONTROLLER_TWO_HANDS","PIQ_FC_ARCADE_CONTROLLER_SINGLE_HAND","PIQ_FC_ARCADE_ZAPPER").get(i),entry.get("name").getAsString());
            var parameter=entry.getAsJsonObject("parameters");
            assertEquals(i<2?"cn/piq/fcarcade/client/ControllerArmPoseParameters":"cn/piq/fcarcade/client/zapper/ZapperArmPoseParameters",parameter.get("class").getAsString());
            assertEquals(List.of("TWO_HANDS","SINGLE_HAND","AIM").get(i),parameter.get("field").getAsString());
        }
        String parameters=source("ControllerArmPoseParameters");
        assertTrue(parameters.contains("HumanoidModel.ArmPose.class, true"));
        assertTrue(parameters.contains("HumanoidModel.ArmPose.class, false"));
        assertFalse(parameters.contains("Minecraft.getInstance()"));
        assertFalse(parameters.contains("ModItems"));
        String gun=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/zapper/ZapperArmPoseParameters.java"));
        assertTrue(gun.contains("HumanoidModel.ArmPose.class,false"));
        assertFalse(gun.contains("Minecraft.getInstance()"));assertFalse(gun.contains("ModItems"));
    }
    public static void main(String[] args) throws Exception {
        int count=0;var test=new ControllerPoseLayoutTest();
        for(var method:ControllerPoseLayoutTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.invoke(test);count++;}
        System.out.println("Passed "+count+" controller camera/layer/pose policy checks.");
    }
}
