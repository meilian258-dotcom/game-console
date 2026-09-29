// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.UUID;

/** New namespace only. Never opens a .sav alongside the original ROM. */
public final class GbaSaveStore {
    private final Path directory,file,backup;
    public GbaSaveStore(Path root,String romSha)throws IOException {
        if(!romSha.matches("[A-F0-9]{64}"))throw new IOException("ROM identity");
        Path base=root.toAbsolutePath().normalize();checkDirectory(base,true);
        directory=base.resolve(romSha);checkDirectory(directory,true);
        file=directory.resolve("sram.bin");backup=directory.resolve("sram.previous.bin");
    }
    public byte[] load()throws IOException {
        checkDirectory(directory,false);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Files.exists(backup,LinkOption.NOFOLLOW_LINKS)?read(backup):new byte[0];
        try{return read(file);}catch(IOException failure){if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS))return read(backup);throw failure;}
    }
    private byte[] read(Path path)throws IOException {
        checkDirectory(directory,false);
        long size=Files.size(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||size>GbaProtocol.MAX_SAVE||!GbaProtocol.saveSize((int)size))throw new IOException("Invalid GBA save file; preserved");
        byte[] bytes;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(GbaProtocol.MAX_SAVE+1);}if(!GbaProtocol.saveSize(bytes.length))throw new IOException("Save changed");return bytes;
    }
    public void save(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length==0)return;
        checkDirectory(directory,false);
        if(!GbaProtocol.saveSize(bytes.length)||Files.isSymbolicLink(file)||Files.isSymbolicLink(backup))throw new IOException("Save bounds/symlink");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] old;
            try{old=read(file);}catch(IOException damaged){
                // Recovery is durable: keep the good backup and preserve the exact bad file.
                // Never overwrite or read an unbounded corrupt file into memory.
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw damaged;
                read(backup);checkDirectory(directory,false);
                Files.move(file,directory.resolve("sram.corrupt-"+UUID.randomUUID()+".bin"),StandardCopyOption.ATOMIC_MOVE);
                old=null;
            }
            if(old!=null){if(Arrays.equals(old,bytes))return;atomic(backup,old);}
        }
        atomic(file,bytes);
    }
    private void atomic(Path target,byte[] bytes)throws IOException {
        checkDirectory(directory,false);
        Path temp=Files.createTempFile(directory,"pending-",".tmp");
        try{Files.write(temp,bytes,StandardOpenOption.TRUNCATE_EXISTING);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(temp);}
    }
    private static void checkDirectory(Path path,boolean create)throws IOException {
        Path cursor=path.getRoot();
        for(Path part:path){
            cursor=cursor.resolve(part);
            if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)){
                if(!create)throw new IOException("Save directory disappeared");
                Files.createDirectory(cursor);
            }
            var attributes=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attributes.isDirectory()||attributes.isSymbolicLink()||attributes.isOther()||!cursor.toRealPath().equals(cursor))
                throw new IOException("Save directory redirect rejected");
        }
    }
}
