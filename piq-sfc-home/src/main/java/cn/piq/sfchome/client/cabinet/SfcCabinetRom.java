// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client.cabinet;

import cn.piq.sfcarcade.core.SfcRomImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Explicit trusted local file only. No ROM discovery, downloads or cache writes. */
final class SfcCabinetRom {
    private SfcCabinetRom() {}
    static SfcRomImage read(Path supplied)throws IOException{
        if(supplied==null||!supplied.isAbsolute())throw new IOException("请选择绝对路径的 .sfc/.smc 文件");
        Path path=supplied.normalize();
        if(path.getFileName()==null)throw new IOException("请选择 ROM 文件而不是磁盘根目录");
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".sfc")&&!name.endsWith(".smc"))throw new IOException("SFC 核心仅支持 .sfc/.smc 文件");
        Path cursor=path.getRoot();
        for(Path part:path){
            cursor=cursor.resolve(part);
            var attributes=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("ROM 路径不能经过符号链接或重解析目录");
        }
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("ROM 不是普通文件");
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long length=file.size();
            if(length<SfcRomImage.MIN_ROM_BYTES||length>SfcRomImage.MAX_ROM_BYTES+512L)
                throw new IOException("SFC ROM 需为 32 KiB～32 MiB（允许 512 字节 copier header）");
            byte[] bytes=new byte[(int)length];ByteBuffer target=ByteBuffer.wrap(bytes);
            while(target.hasRemaining())if(file.read(target)<0)throw new IOException("ROM 读取期间已改变");
            if(file.size()!=length)throw new IOException("ROM 读取期间已改变");
            return SfcRomImage.fromBytes(bytes);
        }
    }
}
