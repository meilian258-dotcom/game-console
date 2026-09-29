package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ControllerCableGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.ZapperStandGeometry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZapperCableDisconnectTest {
    private static ApplianceRay.Point p(double x,double y,double z){return new ApplianceRay.Point(x,y,z);}
    private static ApplianceRay.Point rotated(ApplianceRay.Point p,int turns){
        var v=RocketArcadeGeometry.rotate(new RocketArcadeGeometry.Point(p.x(),p.y(),p.z()),turns);
        return new ApplianceRay.Point(v.x(),v.y(),v.z());
    }
    @Test void consoleSocketFollowsEveryFcAndSuborLayoutAndRotation(){
        for(var style:ControllerCableGeometry.Style.values()){
            if(style==ControllerCableGeometry.Style.SFC)continue;
            var socket=ControllerCableGeometry.socket(style,1,0);
            var a=p(socket.x(),socket.y(),socket.z()+2);var z=p(socket.x(),socket.y(),socket.z()-.15);
            for(int turns=0;turns<4;turns++)assertTrue(ZapperCableControls.console(style,
                    ApplianceRay.unrotate(rotated(a,turns),turns),ApplianceRay.unrotate(rotated(z,turns),turns)),style+"/"+turns);
        }
    }
    @Test void frontRayP1AvPortAndGunWellDoNotSelectDataCable(){
        var style=ControllerCableGeometry.Style.FAMICOM;var s=ControllerCableGeometry.socket(style,1,0);
        assertFalse(ZapperCableControls.console(style,p(s.x(),s.y(),-1),p(s.x(),s.y(),2)));
        for(double x:new double[]{12.53/16,.5})assertFalse(ZapperCableControls.console(style,p(x,s.y(),2),p(x,s.y(),.2)));
        assertFalse(ZapperCableControls.console(style,p(s.x(),2,.5),p(s.x(),0,.5)));
        assertFalse(ZapperCableControls.console(ControllerCableGeometry.Style.SFC,p(s.x(),s.y(),2),p(s.x(),s.y(),.2)));
    }
    @Test void standSidePortHitsButGunBodyAndOppositeSideRemainBorrowTargets(){
        var s=ZapperStandGeometry.CORD;
        var a=p(s.x()+2,s.y(),s.z());var z=p(s.x()-.1,s.y(),s.z());
        for(int turns=0;turns<4;turns++)assertTrue(ZapperCableControls.stand(
                ApplianceRay.unrotate(rotated(a,turns),turns),ApplianceRay.unrotate(rotated(z,turns),turns)));
        assertFalse(ZapperCableControls.stand(p(s.x()-2,s.y(),s.z()),p(s.x()+1,s.y(),s.z())));
        assertFalse(ZapperCableControls.stand(p(s.x(),2,s.z()),p(s.x(),.2,s.z())));
        assertFalse(ZapperCableControls.stand(p(s.x()+2,.25,s.z()),p(s.x()-.1,.25,s.z())));
    }
    @Test void invalidAndTooShortSegmentsCannotHit(){
        var s=ZapperStandGeometry.CORD;var p=p(s.x()+.05,s.y(),s.z());
        assertFalse(ZapperCableControls.stand(p,p));
        assertFalse(ZapperCableControls.stand(p(Double.NaN,0,0),p));
        assertFalse(ZapperCableControls.stand(p(s.x()+2,s.y(),s.z()),p(s.x()+1,s.y(),s.z())));
        assertFalse(ZapperCableControls.console(null,p,p));assertFalse(ZapperCableControls.stand(null,p));
    }
    @Test void bothEndsAndReentrantRemovalShareExactlyOnePaidRefund(){
        var links=new ZapperStandLinks();var paid=new DataCablePayments();
        var a=new ZapperStandLinks.End("minecraft:overworld",0,64,0,UUID.randomUUID());
        var b=new ZapperStandLinks.End("minecraft:overworld",1,64,0,UUID.randomUUID());
        var link=links.connect(a,b);assertNotNull(link);assertTrue(paid.record(link.id()));
        var first=links.at(a);var simultaneous=links.at(b);
        assertTrue(links.remove(first));assertTrue(paid.claim(first.id()));
        assertFalse(links.remove(simultaneous));assertFalse(paid.claim(simultaneous.id()));
        assertNull(links.at(a));assertNull(links.at(b));
        var free=links.connect(a,b);assertTrue(links.remove(free));assertFalse(paid.claim(free.id()));
    }
    @Test void authorityRevalidatesAfterCallbacksAndUsesNoOpOrCreativeGate()throws Exception{
        var text=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/ZapperStandService.java"));
        var start=text.indexOf("static InteractionResult tryDisconnect(");var end=text.indexOf("private static boolean emptyHands",start);
        var branch=text.substring(start,end);
        for(var guard:new String[]{"BUSY.get()","!emptyHands(p)","!permission(p,b,hit)","!permission(p,c,hit)",
                "p.connection.getConnection()!=connection","l.getBlockEntity(clicked)!=clickedEntity","b.loan()!=loan",
                "b.link()!=original","d.links.at(b.endpoint())!=link","d.links.at(end(c))!=link","!unplugTarget(p,clicked,endpoint)"})assertTrue(branch.contains(guard),guard);
        assertTrue(branch.lastIndexOf("disconnect(b)")>branch.lastIndexOf("!unplugTarget(p,clicked,endpoint)"));
        assertFalse(branch.contains("hasPermissions"));assertFalse(branch.contains("isCreative"));assertFalse(branch.contains("takeCartridge"));
        assertTrue(text.contains("p.getMainHandItem().isEmpty()&&p.getOffhandItem().isEmpty()"));
        assertTrue(text.contains("releaseBorrower(b,false)"));assertTrue(text.contains("if(link!=null&&d.paid.claim(link.id()))"));
        assertTrue(text.contains("Math.min(6.0,p.blockInteractionRange())"));
        assertTrue(text.contains("ClipContext.Block.OUTLINE"));assertTrue(text.contains("Blocks.BARRIER.defaultBlockState()"));
    }
    @Test void socketBranchPrecedesCartridgeShiftAndStandBorrow()throws Exception{
        var h=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeHardware.java"));
        var branch=h.substring(h.indexOf("public static InteractionResult interactConsole(ServerPlayer player, BlockPos pos, BlockHitResult hit)"),h.indexOf("public static InteractionResult interactTv("));
        assertTrue(branch.indexOf("ZapperStandService.tryDisconnect")<branch.indexOf("player.isShiftKeyDown()"));
        var s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/ZapperStandService.java"));
        branch=s.substring(s.indexOf("public static InteractionResult interact("),s.indexOf("public static InteractionResult cable("));
        assertTrue(branch.indexOf("tryDisconnect(p,clicked,hit)")<branch.indexOf("b.dock.take("));
    }
    @Test void unplugRequiresSneakingBeforeAndAfterProtectionCallbacks()throws Exception{
        var text=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/ZapperStandService.java"));
        var branch=text.substring(text.indexOf("static InteractionResult tryDisconnect("),text.indexOf("private static boolean emptyHands"));
        assertEquals(2,branch.split("!p.isShiftKeyDown\\(\\)",-1).length-1);
        assertTrue(branch.indexOf("!p.isShiftKeyDown()")<branch.indexOf("var l=p.serverLevel()"));
        assertTrue(branch.lastIndexOf("!p.isShiftKeyDown()")>branch.indexOf("!permission(p,c,hit)"));
        assertTrue(branch.lastIndexOf("!p.isShiftKeyDown()")<branch.indexOf("disconnect(b)"));
        assertTrue(text.contains("if(p.isShiftKeyDown())"),"Existing held-cable shortcut remains supported");
    }
}
