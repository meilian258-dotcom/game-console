package cn.piq.flashbox.runtime;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded local IPC; deliberately unrelated to any Minecraft multiplayer protocol. */
public final class FlashProtocol {
    public static final int WIDTH = 640, HEIGHT = 480, MAX_LINE = 2 * 1024 * 1024;
    public static final int LEFT = 1, RIGHT = 2, UP = 4, DOWN = 8, ACTION = 16;
    private FlashProtocol() {}
    public static String line(Reader source, int limit) throws IOException {
        StringBuilder text = new StringBuilder(Math.min(1024, limit));
        int c;
        while ((c = source.read()) != -1) {
            if (c == '\n') return text.toString();
            if (text.length() >= limit) throw new IOException("运行器消息超过长度上限");
            if (c != '\r') text.append((char)c);
        }
        if (text.isEmpty()) return null;
        throw new IOException("运行器消息被截断");
    }
    public static void checkPng(byte[] png, int width, int height) throws IOException {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (png.length < 33 || png.length > MAX_LINE || width != WIDTH || height != HEIGHT)
            throw new IOException("运行器画面大小不合法");
        for (int i=0;i<8;i++) if (png[i] != signature[i]) throw new IOException("不是 PNG 画面");
        var header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452
                || header.getInt(16) != width || header.getInt(20) != height)
            throw new IOException("PNG 尺寸与消息不一致");
    }
    public static void checkImage(byte[] image,String format,int width,int height) throws IOException {
        if("png".equals(format)){checkPng(image,width,height);return;}
        if(!"jpeg".equals(format))throw new IOException("不支持的画面格式");
        if(image.length<16 || image.length>MAX_LINE || width!=WIDTH || height!=HEIGHT
                || u8(image,0)!=255 || u8(image,1)!=216 || u8(image,image.length-2)!=255 || u8(image,image.length-1)!=217)
            throw new IOException("JPEG 画面大小或标记不合法");
        // Validate dimensions before any image allocation, not after JPEG decoding.
        int at=2;
        while(at<image.length-2) {
            if(u8(image,at++)!=255)throw new IOException("JPEG 段标记不合法");
            while(at<image.length && u8(image,at)==255)at++;
            if(at>=image.length)break;
            int marker=u8(image,at++);
            if(marker==0 || marker==216 || marker==217 || marker==218 || marker==1 || marker>=208&&marker<=215)
                throw new IOException("JPEG 缺少有效尺寸段");
            if(at+2>image.length)break;
            int length=u16(image,at);
            if(length<2 || length>image.length-at)throw new IOException("JPEG 段长度不合法");
            if(marker==192 || marker==193 || marker==194) {
                if(length<8 || u8(image,at+2)!=8 || u16(image,at+3)!=height || u16(image,at+5)!=width)
                    throw new IOException("JPEG 尺寸与消息不一致");
                int components=u8(image,at+7);
                if((components!=1 && components!=3) || length!=8+3*components)
                    throw new IOException("JPEG 颜色分量不合法");
                return;
            }
            at+=length;
        }
        throw new IOException("JPEG 尺寸段缺失或截断");
    }
    private static int u8(byte[] bytes,int at){return bytes[at]&255;}
    private static int u16(byte[] bytes,int at){return (u8(bytes,at)<<8)|u8(bytes,at+1);}
    public static String keys(int p1, int p2) {
        if (p1 < 0 || p1 > 31 || p2 < 0 || p2 > 31) throw new IllegalArgumentException("Invalid mask");
        return "{\"op\":\"keys\",\"p1\":"+p1+",\"p2\":"+p2+"}";
    }
    public static String mouse(int x, int y, boolean down) {
        if (x<0 || y<0 || x>=WIDTH || y>=HEIGHT) throw new IllegalArgumentException("Invalid pointer");
        return "{\"op\":\"mouse\",\"x\":"+x+",\"y\":"+y+",\"down\":"+down+"}";
    }
}
