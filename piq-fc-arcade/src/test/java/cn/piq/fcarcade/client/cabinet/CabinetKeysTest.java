package cn.piq.fcarcade.client.cabinet;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetKeysTest {
    @Test void tapsAndRepeats(){var k=new CabinetKeys();assertTrue(k.press(74));assertEquals(1,k.player1());assertFalse(k.press(74));assertTrue(k.release(74));assertEquals(0,k.player1());assertTrue(k.press(74));assertTrue(k.release(74));assertFalse(k.press(999));}
    @Test void independentPlayersAndAllBits(){var k=new CabinetKeys();for(int key:new int[]{74,85,259,257,265,264,263,262,75,73,79,80})assertTrue(k.press(key));assertEquals(4095,k.player1());assertEquals(0,k.player2());for(int key:new int[]{70,82,53,50,87,83,65,68,71,84,89,72})assertTrue(k.press(key));assertEquals(4095,k.player2());k.clear();assertEquals(0,k.player1());assertEquals(0,k.player2());assertFalse(k.release(74));}
    @Test void validatesFrameBounds(){assertDoesNotThrow(()->new CabinetFrame(1,1,new int[1],4F/3,0,new short[0]));assertThrows(IllegalArgumentException.class,()->new CabinetFrame(2049,1,new int[2049],1,0,new short[0]));assertThrows(IllegalArgumentException.class,()->new CabinetFrame(1,1,new int[0],1,0,new short[0]));assertThrows(IllegalArgumentException.class,()->new CabinetFrame(1,1,new int[1],Float.NaN,0,new short[0]));assertThrows(IllegalArgumentException.class,()->new CabinetFrame(1,1,new int[1],1,4,new short[0]));assertThrows(IllegalArgumentException.class,()->new CabinetFrame(1,1,new int[1],1,0,new short[1]));}
}
