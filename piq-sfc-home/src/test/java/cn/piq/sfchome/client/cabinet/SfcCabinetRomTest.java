package cn.piq.sfchome.client.cabinet;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetRomTest {
    @TempDir Path root;
    @Test void readsCanonicalRomAndCopierHeaderWithoutWritingSource()throws Exception{byte[] b=new byte[32768+512];b[512]=42;Path file=root.resolve("test.SMC");Files.write(file,b);var r=SfcCabinetRom.read(file);assertEquals(32768,r.payloadLength());assertEquals(512,r.removedHeaderBytes());assertEquals(42,r.copyPayload()[0]);assertArrayEquals(b,Files.readAllBytes(file));}
    @Test void rejectsRelativeWrongExtensionDirectoryAndRoot(){assertThrows(IOException.class,()->SfcCabinetRom.read(Path.of("a.sfc")));assertThrows(IOException.class,()->SfcCabinetRom.read(root.resolve("a.zip")));assertThrows(IOException.class,()->SfcCabinetRom.read(root));assertThrows(IOException.class,()->SfcCabinetRom.read(root.getRoot()));}
    @Test void rejectsMissingAndTooSmallFiles()throws Exception{assertThrows(IOException.class,()->SfcCabinetRom.read(root.resolve("absent.sfc")));Path file=root.resolve("small.sfc");Files.write(file,new byte[100]);assertThrows(IOException.class,()->SfcCabinetRom.read(file));}
    @Test void rejectsOversizeBeforeAllocation()throws Exception{Path file=root.resolve("large.sfc");try(var f=new java.io.RandomAccessFile(file.toFile(),"rw")){f.setLength(32*1024*1024+513L);}assertThrows(IOException.class,()->SfcCabinetRom.read(file));}
}
