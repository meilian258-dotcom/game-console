// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Server-owned slot. Call on bounded IO workers, not on a game/render thread.
 * No path is accepted from packets. A writer lease remains held through final save.
 */
public final class NetplaySaveStore implements AutoCloseable {
    private final Path directory,file,previous;
    private final NetplaySaveState.Identity identity;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    public NetplaySaveStore(Path slotDirectory,NetplaySaveState.Identity identity)throws IOException{
        this.identity=Objects.requireNonNull(identity);
        directory=slotDirectory.toAbsolutePath().normalize();safeDirectory(directory);
        file=directory.resolve("checkpoint.bin");previous=directory.resolve("checkpoint.previous.bin");
        Path lease=directory.resolve("writer.lock");regular(lease);
        channel=FileChannel.open(lease,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{lock=channel.tryLock();if(lock==null)throw new IOException("这个存档正在被另一局使用");}
        catch(IOException|RuntimeException failure){channel.close();throw new IOException("无法取得 Netplay 存档写入锁",failure);}
    }
    public synchronized byte[] read()throws IOException{
        check();safeDirectory(directory);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        byte[] bytes=readFile(file);
        try{NetplaySaveState.decode(bytes,identity);}catch(IllegalArgumentException invalid){throw new IOException(invalid.getMessage(),invalid);}
        return bytes;
    }
    /** Bounded metadata/catalog inspection. Does not acquire a writer or create missing directories. */
    public static byte[] readOnly(Path directory,NetplaySaveState.Identity expected)throws IOException{
        Path absolute=directory.toAbsolutePath().normalize();
        if(!Files.exists(absolute,LinkOption.NOFOLLOW_LINKS))return null;
        // Inspect each existing ancestor without safeDirectory's directory-creation side effect.
        Path cursor=absolute.getRoot();for(Path part:absolute){cursor=cursor.resolve(part);
            var a=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther()||!cursor.toRealPath().equals(cursor))throw new IOException("存档目录被重定向");
        }
        Path target=absolute.resolve("checkpoint.bin");if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return null;
        byte[] bytes=readFile(target);
        try{NetplaySaveState.decode(bytes,expected);}catch(IllegalArgumentException invalid){throw new IOException(invalid.getMessage(),invalid);}
        return bytes;
    }
    public synchronized void write(byte[] bytes)throws IOException{
        check();NetplaySaveState.decode(bytes,identity);
        byte[] old=read();regular(previous);
        if(Arrays.equals(bytes,old))return;
        if(old!=null)atomic(previous,old);
        atomic(file,bytes);
    }
    private void atomic(Path target,byte[] bytes)throws IOException{
        safeDirectory(directory);regular(target);
        Path pending=Files.createTempFile(directory,"checkpoint-",".pending");
        try{
            try(var out=FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            // If the filesystem cannot do this atomically, fail and preserve the old checkpoint.
            Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
    }
    private static byte[] readFile(Path path)throws IOException{
        regular(path);var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()<1||before.size()>NetplaySaveState.MAX_BYTES)throw new IOException("Netplay 存档大小异常");
        byte[] bytes;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes((int)before.size()+1);}
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes.length!=before.size()||after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())
                ||!Objects.equals(after.fileKey(),before.fileKey())||!after.isRegularFile()||after.isSymbolicLink()||after.isOther())throw new IOException("Netplay 存档读取时发生变化");
        return bytes;
    }
    private static void regular(Path path)throws IOException{
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.isSymbolicLink()||a.isOther())throw new IOException("Netplay 存档目标不是普通文件");
    }
    public static void safeDirectory(Path path)throws IOException{
        Path absolute=path.toAbsolutePath().normalize(),cursor=absolute.getRoot();
        for(Path part:absolute){
            cursor=cursor.resolve(part);
            if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(cursor);
            var a=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther()||!cursor.toRealPath().equals(cursor))throw new IOException("Netplay 存档目录被重定向");
        }
    }
    private void check(){if(closed)throw new IllegalStateException("存档已关闭");}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;try{lock.release();}finally{channel.close();}}
}
