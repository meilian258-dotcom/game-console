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
    private final Path root;
    public CabinetGameStore(Path root){this.root=root.toAbsolutePath().normalize();}
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
    public boolean contains(CabinetGameManifest.Entry entry)throws IOException{Path p=object(entry);if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return false;verify(p,entry);return true;}
    public Path temporary(UUID request,int file)throws IOException{
        if(request==null||file<0||file>=CabinetGameManifest.MAX_FILES)throw new IOException("Invalid staging identity");
        directory(root);checkQuota(0);Path path=root.resolve("upload-"+request+"-"+file+".part");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Transfer staging collision");return path;
    }
    public void append(Path temporary,int offset,byte[] bytes)throws IOException{
        if(!staging(temporary))throw new IOException("Invalid staging path");
        if(bytes==null||bytes.length<1||bytes.length>CabinetGameManifest.CHUNK||offset<0||offset>CabinetGameManifest.MAX_FILE-bytes.length)throw new IOException("Invalid staging range");
        directory(root);if(offset==0){checkQuota(bytes.length);try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){write(out,bytes);}}
        else{regular(temporary);if(Files.size(temporary)!=offset)throw new IOException("Unexpected upload offset");checkQuota(bytes.length);
            try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){out.position(offset);write(out,bytes);}}
    }
    private static void write(SeekableByteChannel out,byte[] bytes)throws IOException{ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);}
    public void commit(Path temporary,CabinetGameManifest.Entry entry)throws IOException{
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
        if(additional<0||additional>QUOTA)throw new IOException("Invalid quota reservation");
        directory(root);long bytes=0;int count=0;
        try(var paths=Files.list(root)){for(Path p:paths.limit(MAX_OBJECTS+1).toList()){
            regular(p);count++;bytes=Math.addExact(bytes,Files.size(p));}}
        if(count>=MAX_OBJECTS||bytes>QUOTA-additional)throw new IOException("Shared game cache quota reached; administrator cleanup required");
    }
    public void discard(Path temporary)throws IOException{
        if(staging(temporary)&&Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){regular(temporary);Files.delete(temporary);}
    }
    private boolean staging(Path p){return p!=null&&root.equals(p.toAbsolutePath().normalize().getParent())&&p.getFileName().toString().matches("upload-[0-9a-f-]{36}-[0-2]\\.part");}
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
