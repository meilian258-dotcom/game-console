// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
public final class MdRom {
    public static final int MAX=8*1024*1024;
    public static boolean accepts(String name){String n=name.toLowerCase(Locale.ROOT);return n.endsWith(".md")||n.endsWith(".bin")||n.endsWith(".gen");}
    public static byte[] read(Path path)throws IOException{
        path=path.toAbsolutePath().normalize();
        if(!accepts(path.getFileName().toString()))throw new IOException("MD 使用 .md/.bin/.gen 原始卡带");
        Path at=path.getRoot();
        for(Path part:path){at=at.resolve(part);var a=Files.readAttributes(at,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||a.isOther())throw new IOException("ROM 路径重定向被拒绝");}
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()<512||a.size()>MAX)throw new IOException("MD ROM 需为512字节至8MiB普通文件");
        byte[] bytes;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(MAX+1);}validate(bytes);return bytes;
    }
    public static void validate(byte[] bytes)throws IOException{
        if(bytes.length<512||bytes.length>MAX||(bytes.length&1)!=0)throw new IOException("MD ROM 大小无效");
        String header=new String(bytes,256,16,StandardCharsets.US_ASCII);
        if(!header.startsWith("SEGA")||header.contains("32X")||header.contains("PICO")||header.contains("CD"))throw new IOException("当前只支持普通 MD 原始卡带，不支持 CD/32X/Pico/SMD");
        long pc=((bytes[4]&255L)<<24)|((bytes[5]&255L)<<16)|((bytes[6]&255L)<<8)|(bytes[7]&255L);
        if(pc<512||pc>=bytes.length||(pc&1)!=0)throw new IOException("MD 复位入口无效");
    }
    private MdRom(){}
}
