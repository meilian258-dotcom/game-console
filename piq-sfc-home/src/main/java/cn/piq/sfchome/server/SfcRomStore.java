package cn.piq.sfchome.server;
import cn.piq.sfcarcade.core.SfcRomImage;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;

/** Addon-owned library. Uses public SFC normalization but never follows user file links. */
final class SfcRomStore {
    private final Path root;
    private final SfcServerContentPaths.Location content;
    SfcRomStore(Path root){this.root=root.toAbsolutePath().normalize();content=null;}
    SfcRomStore(SfcServerContentPaths.Location content){this.content=Objects.requireNonNull(content);root=content.root();}
    private void directory()throws IOException{if(content!=null)content.prepare();SfcServerContentPaths.directory(root,true);}
    List<SfcHomeNetwork.RomEntry> list()throws IOException{
        directory();var result=new ArrayList<SfcHomeNetwork.RomEntry>();
        try(var paths=Files.list(root)){for(Path p:paths.sorted().limit(512).toList()){
            String n=p.getFileName().toString();if(!SfcRomRepository.isRomPath(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||n.length()>128)continue;
            try{byte[] bytes=readPath(p);SfcRomImage rom=SfcRomImage.fromBytes(bytes);result.add(new SfcHomeNetwork.RomEntry(rom.sha256(),n,rom.payloadLength()));if(result.size()==256)break;}catch(IOException|IllegalArgumentException ignored){}
        }}return List.copyOf(result);
    }
    byte[] read(String hash)throws IOException{
        SfcHomeNetwork.hash(hash,false);directory();
        // Re-read and rehash the actual opened file, not a stale catalog path.
        try(var paths=Files.list(root)){for(Path p:paths.sorted().limit(512).toList())if(SfcRomRepository.isRomPath(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))try{SfcRomImage rom=SfcRomImage.fromBytes(readPath(p));if(rom.sha256().equals(hash))return rom.copyPayload();}catch(IOException|IllegalArgumentException ignored){}}
        throw new IOException("服务器找不到此 SFC ROM");
    }
    void store(String name,String hash,byte[] bytes)throws IOException{
        directory();SfcRomImage rom=SfcRomImage.fromBytes(bytes);if(!rom.sha256().equals(hash))throw new IOException("ROM 校验不一致");
        // Content-addressed immutable path avoids overwriting a different game's file or a link.
        Path target=root.resolve(hash+".sfc");if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){if(!SfcRomImage.fromBytes(readPath(target)).sha256().equals(hash))throw new IOException("ROM 目标文件冲突");return;}
        long total=0;int count=0;try(var paths=Files.list(root)){for(Path p:paths.limit(513).toList())if(SfcRomRepository.isRomPath(p)&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)){count++;total+=Files.size(p);}}if(count>=256||total+rom.payloadLength()>2L*1024*1024*1024)throw new IOException("SFC ROM library quota reached");
        Path temporary=root.resolve(".sfc-upload-"+UUID.randomUUID()+".tmp");
        try{try(SeekableByteChannel out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){ByteBuffer b=ByteBuffer.wrap(rom.copyPayload());while(b.hasRemaining())out.write(b);}Files.move(temporary,target);}finally{Files.deleteIfExists(temporary);}
    }
    private byte[] readPath(Path path)throws IOException{
        if(!path.toAbsolutePath().normalize().getParent().equals(root))throw new IOException("ROM escaped library");
        try(SeekableByteChannel in=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){long size=in.size();if(size<32768||size>SfcHomeNetwork.MAX_ROM)throw new IOException("ROM size outside limit");ByteBuffer out=ByteBuffer.allocate((int)size);while(out.hasRemaining()){if(in.read(out)<0)throw new IOException("Truncated ROM");}if(in.read(ByteBuffer.allocate(1))!=-1)throw new IOException("ROM changed while reading");return out.array();}
    }
}
