package cn.piq.gba.item;

import java.io.IOException;

public final class GbaCardRom {
    public static final int MAX_BYTES=32*1024*1024;
    public static void validate(byte[] bytes)throws IOException{
        if(bytes.length<192||bytes.length>MAX_BYTES)throw new IOException("GBA ROM 必须为 192 字节至 32 MiB");
        if((bytes[0xB2]&255)!=0x96)throw new IOException("GBA ROM 标头无效（不接受 BIOS 或其它机型）");
    }
    private GbaCardRom(){}
}
