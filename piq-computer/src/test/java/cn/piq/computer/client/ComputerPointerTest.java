package cn.piq.computer.client;

import java.nio.file.*;
import net.minecraft.client.MouseHandler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerPointerTest {
    @Test void initializesAtClickAndReachesEveryEdge(){
        var p=new ComputerPointer();p.position(100,100);assertEquals(100,p.x());assertEquals(100,p.y());
        p.move(-10000,-10000,.75);assertEquals(0,p.x());assertEquals(0,p.y());
        p.move(10000,10000,.75);assertEquals(639,p.x());assertEquals(479,p.y());
        p.move(-4,-4,.75);assertEquals(636,p.x());assertEquals(476,p.y());
        p.reset();assertEquals(320,p.x());assertEquals(240,p.y());
    }
    @Test void fractionalMotionDoesNotDependOnEventBatching(){
        var a=new ComputerPointer();var b=new ComputerPointer();
        for(int i=0;i<100;i++)a.move(.5,-.5,.25);
        b.move(50,-50,.25);assertEquals(b.x(),a.x());assertEquals(b.y(),a.y());
    }
    @Test void ignoresNonFiniteValuesAndClampsSensitivity(){
        var p=new ComputerPointer();p.move(Double.NaN,4,1);p.move(4,Double.POSITIVE_INFINITY,1);p.move(4,4,Double.NaN);assertEquals(320,p.x());assertEquals(240,p.y());
        p.move(8,-8,0);assertEquals(322,p.x());assertEquals(238,p.y());
        p.move(8,-8,99);assertEquals(338,p.x());assertEquals(222,p.y());
    }
    @Test void logicalCursorRoundTripsAllTelevisionFacings(){
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true})for(int t=0;t<4;t++){
            var s=ComputerScreenRayTest.scene(wide,wall,t);
            for(int x:new int[]{0,160,320,639})for(int y:new int[]{0,120,240,479}){
                var end=ComputerScreenRayTest.at(s,x/639.0,y/479.0);var eye=ComputerScreenRayTest.at(s,.5,.5).add(ComputerScreenRayTest.v(s.surface().image().normal()).scale(2));
                var dir=end.subtract(eye);
                var hit=cn.piq.fcarcade.layout.ScreenRayMapping.hit(s.surface().image(),new cn.piq.fcarcade.layout.RocketArcadeGeometry.Point(eye.x,eye.y,eye.z),new cn.piq.fcarcade.layout.RocketArcadeGeometry.Point(dir.x,dir.y,dir.z),8,640,480).orElseThrow();
                assertEquals(x,hit.x());assertEquals(y,hit.y());assertTrue(ComputerScreenRayTest.clear(s,eye,end));
            }
        }
    }
    @Test void realMinecraftMouseMixinTargetsExist()throws Exception{
        assertEquals(void.class,MouseHandler.class.getDeclaredMethod("turnPlayer",double.class).getReturnType());
        for(String name:new String[]{"accumulatedDX","accumulatedDY"})assertEquals(double.class,MouseHandler.class.getDeclaredField(name).getType());
        for(String name:new String[]{"smoothTurnX","smoothTurnY"})assertEquals(net.minecraft.util.SmoothDouble.class,MouseHandler.class.getDeclaredField(name).getType());
    }
    @Test void entryWaitsForAimClickInsteadOfMovingCamera()throws Exception{
        var s=Files.readString(Path.of("src/main/java/cn/piq/computer/client/ComputerWorldInput.java"));
        assertTrue(s.contains("GLFW.GLFW_MOUSE_BUTTON_RIGHT"));assertTrue(s.contains("CAPTURE.begin(keysDown(),buttonsDown())"));
        assertFalse(s.contains("setYRot"));assertFalse(s.contains("setXRot"));assertTrue(s.contains("if(!controlling())return true"));
        var mixin=Files.readString(Path.of("src/main/java/cn/piq/computer/client/mixin/ComputerMouseMixin.java"));
        assertTrue(mixin.contains("accumulatedDX=accumulatedDY=0"));assertTrue(mixin.contains("smoothTurnX.reset()"));
    }
}
