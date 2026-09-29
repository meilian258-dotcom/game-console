package cn.piq.fcarcade.client.cabinet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetImmersiveInputTest {
    @Test void allTwelveOnePlayerButtons(){var input=new CabinetImmersiveInput();input.activate(true);for(int key:new int[]{74,85,259,257,265,264,263,262,75,73,79,80})assertTrue(input.key(key,1));assertEquals(4095,input.mask());}
    @Test void movementAndFormerSecondPlayerNeverEnterEmulator(){var input=new CabinetImmersiveInput();input.activate(true);for(int key:new int[]{87,83,65,68,70,82,53,50,71,84,89,72,32,340})assertFalse(input.key(key,1));assertEquals(0,input.mask());}
    @Test void fastTapsAreEdgesNotTickPolling(){var input=new CabinetImmersiveInput();input.activate(true);for(int i=0;i<100;i++){assertTrue(input.key(74,1));assertEquals(1,input.mask());assertFalse(input.key(74,2));assertTrue(input.key(74,0));assertEquals(0,input.mask());}}
    @Test void focusOrGuiClearDoesNotResumeHeldRepeat(){var input=new CabinetImmersiveInput();input.activate(true);input.key(74,1);assertTrue(input.activate(false));assertEquals(0,input.mask());assertFalse(input.activate(false));assertFalse(input.key(74,1));input.activate(true);assertFalse(input.key(74,2));assertFalse(input.key(74,0));assertEquals(0,input.mask());assertTrue(input.key(74,1));}
    @Test void newSessionCannotInheritKeys(){var input=new CabinetImmersiveInput();input.activate(true);input.key(74,1);input.reset();assertEquals(0,input.mask());assertFalse(input.key(74,1));input.activate(true);assertFalse(input.key(74,2));}
}
