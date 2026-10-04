package cn.piq.fcarcade.cabinet;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Content-addressed bounded storage. All calls belong to a bounded IO worker, never render/server tick. */
public final class CabinetGameStore {
    public static final long QUOTA=2L*1024*1024*1024;
    public static final int MAX_OBJECTS=768;
    private final Path root, legacy;
    public CabinetGameStore(Path root){this(root,null);}
    /** Legacy is a read-only source for this world's authorized manifest, not a directory catalog. */
    public CabinetGameStore(Path root,Path legacy){this.root=root.toAbsolutePath().normalize();this.legacy=legacy==null?null:legacy.toAbsolutePath().normalize();}
    public static void directory(Path directory)throws IOException{
        walkDirectory(directory,true);
    }
    /** Validation of source ROM paths must never create missing source directories. */
    public static void requireDirectory(Path directory)throws IOException{
        walkDirectory(directory,false);
    }
    private static void walkDirectory(Path directory,boolean create)throws IOException{
        Path absolute=directory.toAbsolutePath().normalize(),p=absolute.getRoot();
        for(Path part:absolute){p=p.resolve(part);if(create&&!Files.exists(p,LinkOption.NOFOLLOW_LINKS))try{Files.createDirectory(p);}catch(FileAlreadyExistsException ignored){}
            BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther())throw new IOException("Game storage must not contain links or junctions");}
    }
    private Path object(CabinetGameManifest.Entry entry)throws IOException{directory(root);return root.resolve(entry.sha256()+".data");}
    public boolean contains(CabinetGameManifest.Entry entry)throws IOException{
        Path p=object(entry);
        if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){verify(p,entry);return true;}
        if(legacy==null||legacy.equals(root)||!Files.exists(legacy,LinkOption.NOFOLLOW_LINKS))return false;
        requireDirectory(legacy);Path old=legacy.resolve(entry.sha256()+".data");
        if(!Files.exists(old,LinkOption.NOFOLLOW_LINKS))return false;
        // Only a file named by a live authorized manifest is copied. Never scan other worlds,
        // move a component, or delete a legacy ROM/BIOS/save during compatibility reads.
        synchronized(CabinetGameStore.class){
            if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){verify(p,entry);return true;}
            verify(old,entry);checkQuota(entry.size());Path temporary=temporary(UUID.randomUUID(),0);
            try{
                try(var in=Files.newByteChannel(old,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                    var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){
                    ByteBuffer buffer=ByteBuffer.allocate(32768);long total=0;
                    while(true){int n=in.read(buffer);if(n<0)break;if(n==0)continue;total+=n;if(total>entry.size())throw new IOException("Legacy game grew while copying");buffer.flip();while(buffer.hasRemaining())out.write(buffer);buffer.clear();}
                }
                verify(old,entry);commit(temporary,entry);
            }finally{discard(temporary);}
        }
        return true;
    }
    public Path verifiedPath(CabinetGameManifest.Entry entry)throws IOException{
        if(!contains(entry))throw new IOException("服务器缺少游戏或 BIOS："+entry.name());return object(entry);
    }
    public Path temporary(UUID request,int file)throws IOException{
        if(request==null||file<0||file>=CabinetGameManifest.MAX_FILES)throw new IOException("Invalid staging identity");
        directory(root);checkQuota(0);Path path=root.resolve("upload-"+request+"-"+file+".part");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Transfer staging collision");return path;
    }
    public void append(Path temporary,int offset,byte[] bytes)throws IOException{
        synchronized(CabinetGameStore.class){appendLocked(temporary,offset,bytes);}
    }
    private void appendLocked(Path temporary,int offset,byte[] bytes)throws IOException{
        if(!staging(temporary))throw new IOException("Invalid staging path");
        if(bytes==null||bytes.length<1||bytes.length>CabinetGameManifest.CHUNK||offset<0||offset>CabinetGameManifest.MAX_FILE-bytes.length)throw new IOException("Invalid staging range");
        directory(root);if(offset==0){checkQuota(bytes.length);try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){write(out,bytes);}}
        else{regular(temporary);if(Files.size(temporary)!=offset)throw new IOException("Unexpected upload offset");checkQuota(bytes.length);
            try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){out.position(offset);write(out,bytes);}}
    }
    private static void write(SeekableByteChannel out,byte[] bytes)throws IOException{ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);}
    public void commit(Path temporary,CabinetGameManifest.Entry entry)throws IOException{
        synchronized(CabinetGameStore.class){commitLocked(temporary,entry);}
    }
    private void commitLocked(Path temporary,CabinetGameManifest.Entry entry)throws IOException{
        if(!staging(temporary))throw new IOException("Invalid staging path");
        verify(temporary,entry);Path target=object(entry);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(target,entry);Files.delete(temporary);return;}
        Files.move(temporary,target);verify(target,entry);
    }
    public byte[] chunk(CabinetGameManifest.Entry entry,int offset)throws IOException{
        Path p=object(entry);regular(p);if(offset<0||offset>=entry.size()||Files.size(p)!=entry.size())throw new IOException("Invalid download range");
        int count=Math.min(CabinetGameManifest.CHUNK,entry.size()-offset);byte[] bytes=new byte[count];
        try(var in=Files.newByteChannel(p,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){in.position(offset);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())if(in.read(b)<0)throw new EOFException("Game truncated");}
        return bytes;
    }
    public void checkQuota(long additional)throws IOException{
        synchronized(CabinetGameStore.class){checkQuotaLocked(additional);}
    }
    private void checkQuotaLocked(long additional)throws IOException{
        if(additional<0||additional>QUOTA)throw new IOException("Invalid quota reservation");
        directory(root);long bytes=0;int count=0;
        try(var paths=Files.list(root)){for(Path p:paths.limit(MAX_OBJECTS+1).toList()){
            regular(p);count++;bytes=Math.addExact(bytes,Files.size(p));}}
        if(count>=MAX_OBJECTS||bytes>QUOTA-additional)throw new IOException("Shared game cache quota reached; administrator cleanup required");
    }
    public void discard(Path temporary)throws IOException{
        synchronized(CabinetGameStore.class){if(staging(temporary)&&Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){regular(temporary);Files.delete(temporary);}}
    }
    private boolean staging(Path p){
        if(p==null||!root.equals(p.toAbsolutePath().normalize().getParent()))return false;
        var match=java.util.regex.Pattern.compile("upload-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-([0-9]+)\\.part").matcher(p.getFileName().toString());
        if(!match.matches())return false;
        try{int index=Integer.parseInt(match.group(2));return index>=0&&index<CabinetGameManifest.MAX_FILES&&match.group(2).equals(Integer.toString(index));}catch(NumberFormatException invalid){return false;}
    }
    public static BasicFileAttributes regular(Path p)throws IOException{
        Path absolute=p.toAbsolutePath().normalize();if(absolute.getParent()!=null)requireDirectory(absolute.getParent());
        BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.isSymbolicLink()||a.isOther())throw new IOException("Game file is not an ordinary non-link file");return a;
    }
    public static void verify(Path p,CabinetGameManifest.Entry entry)throws IOException{
        BasicFileAttributes before=regular(p);if(before.size()!=entry.size())throw new IOException("Game size mismatch");
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(var in=Files.newByteChannel(p,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){ByteBuffer buffer=ByteBuffer.allocate(32768);long total=0;while(true){int read=in.read(buffer);if(read<0)break;if(read==0)continue;total+=read;if(total>entry.size())throw new IOException("Game grew while reading");buffer.flip();digest.update(buffer);buffer.clear();}if(total!=entry.size())throw new IOException("Game truncated");}
        BasicFileAttributes after=regular(p);
        if(after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())||!Objects.equals(after.fileKey(),before.fileKey())||!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))throw new IOException("Game hash mismatch or changed during read");
    }
}
