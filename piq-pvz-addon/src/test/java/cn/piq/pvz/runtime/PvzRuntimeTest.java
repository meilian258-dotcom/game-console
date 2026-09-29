package cn.piq.pvz.runtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class PvzRuntimeTest {
    @TempDir Path root;
    private byte[] h(int w,int height,int size,int audio){return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x315A5650).putInt(1).putInt(w).putInt(height).putInt(size).putInt(audio).array();}
    @Test void videoHeader() throws Exception {assertEquals(800,PvzRuntime.header(h(800,600,1920000,1470))[2]);}
    @Test void audioOnly() throws Exception {assertEquals(0,PvzRuntime.header(h(0,0,0,1470))[4]);}
    @Test void badMagic(){byte[] v=h(800,600,1920000,0);v[0]=0;assertThrows(IOException.class,()->PvzRuntime.header(v));}
    @Test void partialHeader(){assertThrows(IOException.class,()->PvzRuntime.header(new byte[23]));}
    @Test void oversizedVideo(){assertThrows(IOException.class,()->PvzRuntime.header(h(800,600,Integer.MAX_VALUE,0)));}
    @Test void unsupportedResolution(){assertThrows(IOException.class,()->PvzRuntime.header(h(640,480,1920000,0)));}
    @Test void oddAudio(){assertThrows(IOException.class,()->PvzRuntime.header(h(0,0,0,1)));}
    @Test void hugeAudio(){assertThrows(IOException.class,()->PvzRuntime.header(h(0,0,0,16386)));}
    @Test void negativeAudio(){assertThrows(IOException.class,()->PvzRuntime.header(h(0,0,0,-2)));}
    @Test void rejectsGeometryWithoutPixels(){assertThrows(IOException.class,()->PvzRuntime.header(h(800,600,0,0)));}
    @Test void boundedCopy() throws Exception {Path source=root.resolve("s"),to=root.resolve("d");Files.write(source,new byte[]{1,2,3});PvzRuntime.copyBounded(source,to,3);assertArrayEquals(Files.readAllBytes(source),Files.readAllBytes(to));}
    @Test void copyRefusesOversize() throws Exception {Path source=root.resolve("s");Files.write(source,new byte[5]);assertThrows(IOException.class,()->PvzRuntime.copyBounded(source,root.resolve("d"),4));assertFalse(Files.exists(root.resolve("d")));}
    @Test void copyDoesNotOverwrite() throws Exception {Path source=root.resolve("s"),to=root.resolve("d");Files.write(source,new byte[]{1});Files.write(to,new byte[]{2});assertThrows(IOException.class,()->PvzRuntime.copyBounded(source,to,4));assertArrayEquals(new byte[]{2},Files.readAllBytes(to));}
    @Test void emptyContentRefused() throws Exception {Path source=Files.createFile(root.resolve("s"));assertThrows(IOException.class,()->PvzRuntime.copyBounded(source,root.resolve("d"),4));}
    @Test void fixedCommand(){var b=ByteBuffer.wrap(PvzRuntime.command(1,256,-32768,32767,5)).order(ByteOrder.LITTLE_ENDIAN);assertEquals(24,b.remaining());assertEquals(0x315A5650,b.getInt());assertEquals(1,b.getInt());assertEquals(256,b.getInt());assertEquals(-32768,b.getInt());assertEquals(32767,b.getInt());assertEquals(5,b.getInt());}
    @Test void hash() throws Exception {Path s=root.resolve("s");Files.writeString(s,"abc");assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",PvzRuntime.sha(s));}
    @Test void pinsAreFixed() {assertTrue(PvzRuntime.CORE_SHA.matches("[0-9A-F]{64}"));assertTrue(PvzRuntime.HOST_SHA.matches("[0-9A-F]{64}"));assertNotEquals(PvzRuntime.CORE_SHA,PvzRuntime.HOST_SHA);}
}
