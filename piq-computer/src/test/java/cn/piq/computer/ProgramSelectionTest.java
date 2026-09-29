package cn.piq.computer;
import cn.piq.computer.client.FlashComputerBackend;
import cn.piq.computer.flash.FlashProtocol;
import java.io.*;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProgramSelectionTest {
    @Test void filesBelongToExplicitBackend(){assertTrue(ProgramKind.FLASH.accepts(Path.of("Demo.SWF")));assertFalse(ProgramKind.FLASH.accepts(Path.of("main.pak")));assertTrue(ProgramKind.PVZ.accepts(Path.of("main.pak")));assertFalse(ProgramKind.PVZ.accepts(Path.of("other.pak")));assertFalse(ProgramKind.HARDWARE.accepts(Path.of("demo.swf")));}
    @Test void unknownConfigNeverExecutes(){assertEquals(ProgramKind.HARDWARE,ProgramKind.parse("UNKNOWN"));assertEquals(ProgramKind.HARDWARE,ProgramKind.parse(null));assertEquals(ProgramKind.FLASH,ProgramKind.parse("FLASH"));}
    @Test void twoFlashGroupsIndependent(){var held=Set.of(262,87,340);assertEquals(2,FlashComputerBackend.mask(held,263,262,265,264,32));assertEquals(20,FlashComputerBackend.mask(held,65,68,87,83,340));assertEquals(0,FlashComputerBackend.mask(Set.of(),65,68,87,83,340));}
    @Test void bridgeRetainsBoundedIpc()throws Exception{assertEquals("a",FlashProtocol.line(new StringReader("a\n"),1));assertThrows(IOException.class,()->FlashProtocol.line(new StringReader("aa\n"),1));assertThrows(IOException.class,()->FlashProtocol.line(new StringReader("a"),20));assertThrows(IllegalArgumentException.class,()->FlashProtocol.keys(32,0));assertThrows(IllegalArgumentException.class,()->FlashProtocol.mouse(640,240,false));}
    @Test void idleInputDoesNotPollGlfw(){var s=new InputCapture();assertFalse(s.needsPoll());s.begin(Set.of(65),Set.of());assertTrue(s.needsPoll());s.end();assertTrue(s.needsPoll());s.sample(Set.of(),Set.of());assertFalse(s.needsPoll());}
}
