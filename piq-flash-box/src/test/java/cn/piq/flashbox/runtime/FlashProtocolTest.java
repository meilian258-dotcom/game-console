package cn.piq.flashbox.runtime;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlashProtocolTest {
    @Test void boundedLinesAndEof() throws Exception {
        assertEquals("hello", FlashProtocol.line(new StringReader("hello\r\n"), 6));
        assertNull(FlashProtocol.line(new StringReader(""), 8));
        assertThrows(IOException.class, ()->FlashProtocol.line(new StringReader("partial"), 20));
        assertThrows(IOException.class, ()->FlashProtocol.line(new StringReader("overflow\n"), 4));
    }
    @Test void masksAreBounded() {
        assertTrue(FlashProtocol.keys(0,31).contains("31"));
        assertThrows(IllegalArgumentException.class, ()->FlashProtocol.keys(32,0));
        assertThrows(IllegalArgumentException.class, ()->FlashProtocol.keys(0,-1));
    }
    @Test void pointerBounds() {
        assertTrue(FlashProtocol.mouse(639,479,true).contains("true"));
        assertThrows(IllegalArgumentException.class, ()->FlashProtocol.mouse(640,0,false));
    }
    @Test void pngHeaderMustMatchFixedCapture() throws Exception {
        byte[] png = new byte[33];
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        System.arraycopy(signature,0,png,0,8);
        var b=ByteBuffer.wrap(png);b.putInt(8,13);b.putInt(12,0x49484452);b.putInt(16,640);b.putInt(20,480);
        FlashProtocol.checkPng(png,640,480);
        b.putInt(16,Integer.MAX_VALUE);
        assertThrows(IOException.class, ()->FlashProtocol.checkPng(png,640,480));
        assertThrows(IOException.class, ()->FlashProtocol.checkPng(new byte[0],640,480));
    }
}
