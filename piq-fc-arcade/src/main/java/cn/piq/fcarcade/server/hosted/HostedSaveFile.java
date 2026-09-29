package cn.piq.fcarcade.server.hosted;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** New hosted namespace only; exclusive writer, bounded checksum envelope, corrupt originals preserved. */
public final class HostedSaveFile implements AutoCloseable {
    private static final int MAGIC=0x50514831;
    private final Path directory,file,previous;private final int maximum;
    private final FileChannel channel;private final FileLock lease;
    public HostedSaveFile(Path root,String romSha,String compatibility,int maximum)throws IOException{
        if(romSha==null||!romSha.matches("[a-fA-F0-9]{64}")||compatibility==null||compatibility.isBlank()||maximum<1||maximum>16*1024*1024)throw new IllegalArgumentException("Hosted save identity/bounds");
        this.maximum=maximum;directory=root.toAbsolutePath().normalize().resolve(romSha.toLowerCase(java.util.Locale.ROOT)).resolve(ServerCoreFiles.sha256(compatibility.getBytes(StandardCharsets.UTF_8)));
        ServerCoreFiles.directory(directory,true);file=directory.resolve("state.bin");previous=directory.resolve("state.previous.bin");
        channel=FileChannel.open(directory.resolve("writer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired=null;try{acquired=channel.tryLock();if(acquired==null)throw new IOException("Hosted save is already in use");}catch(Exception failure){channel.close();if(failure instanceof IOException io)throw io;throw new IOException("Hosted save is already in use",failure);}lease=acquired;
    }
    public byte[] load()throws IOException{
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new byte[0];
        return decode(ServerCoreFiles.read(file,40,maximum+40));
    }
    private byte[] decode(byte[] envelope)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(envelope))){
            if(in.readInt()!=MAGIC)throw new IOException("Hosted save header mismatch; original preserved");
            int count=in.readInt();if(count<1||count>maximum||envelope.length!=40+count)throw new IOException("Hosted save size mismatch; original preserved");
            byte[] expected=in.readNBytes(32),data=in.readNBytes(count);
            if(!MessageDigest.isEqual(expected,java.util.HexFormat.of().parseHex(ServerCoreFiles.sha256(data))))throw new IOException("Hosted save checksum mismatch; original preserved");return data;
        }
    }
    public void save(byte[] bytes)throws IOException{
        if(bytes==null||bytes.length==0)return;if(bytes.length>maximum)throw new IOException("Hosted save exceeds limit");
        ServerCoreFiles.directory(directory,false);
        byte[] old=null;if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){old=ServerCoreFiles.read(file,40,maximum+40);if(Arrays.equals(decode(old),bytes))return;}
        var buffer=new ByteArrayOutputStream(bytes.length+40);try(var out=new DataOutputStream(buffer)){out.writeInt(MAGIC);out.writeInt(bytes.length);out.write(java.util.HexFormat.of().parseHex(ServerCoreFiles.sha256(bytes)));out.write(bytes);}
        if(old!=null)atomic(previous,old);atomic(file,buffer.toByteArray());
    }
    private void atomic(Path target,byte[] bytes)throws IOException{
        ServerCoreFiles.directory(directory,false);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Hosted save target redirected");
        Path temp=Files.createTempFile(directory,"pending-",".tmp");
        try{try(FileChannel output=FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);}Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    @Override public void close(){try{lease.release();}catch(IOException ignored){}try{channel.close();}catch(IOException ignored){}}
}
