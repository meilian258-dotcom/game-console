package cn.piq.computer;
import cn.piq.computer.client.ComputerHdmiMesh;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ComputerGeometryTest {
    @Test void scaledPowerAndGpuCoordinatesRoundTrip(){for(var v:new Vec3[]{new Vec3(10.75/16,14.3/16,.63/16),new Vec3(11.1/16,7.105/16,15.35/16),new Vec3(0,0,0),new Vec3(1,1.375,1)})assertTrue(v.distanceTo(ComputerGeometry.unscaled(ComputerGeometry.scaled(v)))<1e-12);}
    @Test void hdmiIsCenteredInTheRightGpuPort(){assertEquals(11.1/16,ComputerGeometry.unscaled(ComputerGeometry.HDMI).x,1e-12);assertEquals(7.105/16,ComputerGeometry.unscaled(ComputerGeometry.HDMI).y,1e-12);}
    @Test void hdmiNotAvAndWidePanelOffset(){var a=ComputerHdmiMesh.television(ArcadeDisplayStyle.HOME_PANEL_2,0);var b=ComputerHdmiMesh.television(ArcadeDisplayStyle.HOME_PANEL_3,0);assertEquals(7.735/16,a.y,1e-12);assertEquals(1,b.x-a.x,1e-12);assertEquals(9.4/16,a.z,1e-12);}
    @Test void allSixteenDirectionsProduceFiniteBoundedCables(){for(int p=0;p<4;p++)for(int t=0;t<4;t++){var q=ComputerHdmiMesh.build(p,ArcadeDisplayStyle.HOME_PANEL_2,t,new BlockPos(3,1,2));assertFalse(q.isEmpty());assertTrue(q.size()<1000);for(var f:q)for(var v:new cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point[]{f.a(),f.b(),f.c(),f.d()})assertTrue(Double.isFinite(v.x()+v.y()+v.z()));}}
}
