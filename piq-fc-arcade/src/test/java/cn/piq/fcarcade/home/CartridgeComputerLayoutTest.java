package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeComputerLayoutTest {
    @Test void workstationFitsOneCellWithGroundedCaseAndRecessedMonitor() {
        var b=CartridgeComputerLayout.bounds(0);
        assertEquals(.65,b.minX());assertEquals(0,b.minY());assertEquals(.6,b.minZ());
        assertEquals(15,b.maxX());assertEquals(13.5,b.maxY());assertEquals(14.6,b.maxZ());
        assertEquals(6,CartridgeComputerLayout.parts(0).size());
    }
    @Test void individualPartsLeaveUsefulAirAroundMouseAndKeyboard() {
        var parts=CartridgeComputerLayout.parts(0);var monitor=parts.get(1);var keyboard=parts.get(2);var mouse=parts.get(3);
        assertTrue(keyboard.maxY()<monitor.minY());assertTrue(keyboard.maxZ()<monitor.minZ());
        // Front is low Z: low X is the viewer's right, where the mouse belongs.
        assertTrue(mouse.maxX()<keyboard.minX());assertTrue(mouse.maxY()<monitor.minY());
        assertEquals(13.5,monitor.maxY());assertEquals(5.25,monitor.minZ());
    }
    @Test void allFacingsAreExactQuarterTurnsAndPreserveEachPart() {
        for(int t=0;t<4;t++)for(int n=0;n<6;n++) {
            var base=CartridgeComputerLayout.parts(0).get(n);var b=CartridgeComputerLayout.parts(t).get(n);
            double ax=base.minX(),az=base.minZ(),cx=base.maxX(),cz=base.maxZ();
            for(int i=0;i<t;i++){double a=ax,c=cx;ax=16-az;az=a;cx=16-cz;cz=c;}
            assertEquals(Math.min(ax,cx),b.minX());assertEquals(Math.max(ax,cx),b.maxX());
            assertEquals(Math.min(az,cz),b.minZ());assertEquals(Math.max(az,cz),b.maxZ());
            assertEquals(base.minY(),b.minY());assertEquals(base.maxY(),b.maxY());
            assertTrue(b.minX()>=0&&b.maxX()<=16&&b.minZ()>=0&&b.maxZ()<=16);
        }
    }
    @Test void cachedImmutablePartsNormalizeNegativeAndLargeTurns() {
        for(int t=-8;t<12;t++){assertSame(CartridgeComputerLayout.parts(Math.floorMod(t,4)),CartridgeComputerLayout.parts(t));assertSame(CartridgeComputerLayout.bounds(Math.floorMod(t,4)),CartridgeComputerLayout.bounds(t));}
        assertThrows(UnsupportedOperationException.class,()->CartridgeComputerLayout.parts(0).clear());
    }
    public static void main(String[] args)throws Exception {
        int n=0;var instance=new CartridgeComputerLayoutTest();for(var m:CartridgeComputerLayoutTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(instance);n++;}System.out.println("Cartridge computer layout tests passed: "+n);
    }
}
