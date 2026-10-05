// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Copies opaque ZIP files only; never extracts, searches folders, or runs ROM contents. */
final class NativeRomStaging {
    static final List<String> AUXILIARY_NAMES=List.of("neogeo.zip","qsound_hle.zip","qsound.zip","pgm.zip");
    static final long MAX_TOTAL_BYTES=128L*1024*1024;
    private NativeRomStaging(){}

    static Path stage(Path input,String driver,Path owned)throws IOException{
        if(driver==null||!driver.matches("[a-z0-9_]{1,32}"))throw new IOException("Invalid arcade driver name");
        Path source=input.toAbsolutePath().normalize(),destination=owned.toAbsolutePath().normalize();
        rejectLinkedParents(source.getParent());rejectLinkedParents(destination);
        long total=copyBounded(source,destination.resolve(driver+".zip"),0);
        for(String name:AUXILIARY_NAMES){
            if(name.equals(driver+".zip"))continue;
            Path candidate=source.getParent().resolve(name);
            if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))total=copyBounded(candidate,destination.resolve(name),total);
        }
        return destination.resolve(driver+".zip");
    }

    static long checkedTotal(long already,long bytes)throws IOException{
        if(bytes<22||bytes>BridgeProtocol.MAX_ROM)throw new IOException("旧 MAME 串流/进程模式的 ROM 和 BIOS ZIP 限 22 字节至 64 MiB；64–96 MiB 主游戏仅开放 FBNeo JNI Netplay");
        if(already<0||already>MAX_TOTAL_BYTES-bytes)throw new IOException("ROM plus allowed BIOS files exceed 128 MiB");
        return already+bytes;
    }

    private static long copyBounded(Path source,Path target,long total)throws IOException{
        BasicFileAttributes before=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.isSymbolicLink()||before.isOther())throw new IOException("ROM/BIOS must be a regular non-link file");
        long next=checkedTotal(total,before.size());
        long copied=0;
        try(SeekableByteChannel in=Files.newByteChannel(source,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
            SeekableByteChannel out=Files.newByteChannel(target,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){
            ByteBuffer buffer=ByteBuffer.allocate(65536);
            for(int count;(count=in.read(buffer))!=-1;){
                if(count==0)continue;copied+=count;
                if(copied>before.size()||copied>BridgeProtocol.MAX_ROM)throw new IOException("ROM/BIOS grew during staging");
                buffer.flip();while(buffer.hasRemaining())out.write(buffer);buffer.clear();
            }
        }
        BasicFileAttributes after=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(copied!=before.size()||after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())
                ||!Objects.equals(after.fileKey(),before.fileKey())||!after.isRegularFile()||after.isSymbolicLink()||after.isOther())
            throw new IOException("ROM/BIOS changed during staging");
        return next;
    }

    private static void rejectLinkedParents(Path directory)throws IOException{
        for(Path current=directory;current!=null;current=current.getParent()){
            BasicFileAttributes a=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther())throw new IOException("ROM/runtime staging parents may not be links");
        }
    }
}
