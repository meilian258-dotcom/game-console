package cn.piq.fcarcade.cabinet;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded local configuration, never world data. Call file methods only on an I/O worker. */
public final class CabinetRomBindings {
    public static final int MAX_BINDINGS=256,MAX_BYTES=262144;
    private static final int MAGIC=0x50495231;
    private final Path file;
    public record Key(String context,String dimension,UUID device,String backend){
        public Key{
            Objects.requireNonNull(device);
            if(context==null||context.isBlank()||context.length()>2048||context.chars().anyMatch(Character::isISOControl)
                    ||!validId(dimension)||!validId(backend))throw new IllegalArgumentException("Invalid game selection key");
        }
        private static boolean validId(String id){return id!=null&&id.length()<=128&&id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");}
        String digest(){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((context+"\n"+dimension+"\n"+device+"\n"+backend).getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    public CabinetRomBindings(Path file){this.file=Objects.requireNonNull(file).toAbsolutePath().normalize();}
    public synchronized Optional<Path> load(Key key)throws IOException{
        String value=read().get(Objects.requireNonNull(key).digest());
        return value==null?Optional.empty():Optional.of(Path.of(value));
    }
    public synchronized void remember(Key key,Path rom)throws IOException{
        Objects.requireNonNull(key);Objects.requireNonNull(rom);
        Path absolute=rom.toAbsolutePath().normalize();String value=absolute.toString();
        if(value.length()>4096||value.chars().anyMatch(Character::isISOControl))throw new IOException("Invalid saved ROM path");
        Map<String,String> entries=read();String id=key.digest();
        if(!entries.containsKey(id)&&entries.size()>=MAX_BINDINGS)throw new IOException("Game selection table is full");
        entries.put(id,value);
        var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)){
            output.writeInt(MAGIC);output.writeShort(entries.size());
            for(var entry:entries.entrySet()){output.writeUTF(entry.getKey());output.writeUTF(entry.getValue());}
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("Game selection table exceeds its byte budget");
        Files.createDirectories(file.getParent());checkTarget();
        Path temporary=Files.createTempFile(file.getParent(),".piq-retro-selection-",".tmp");
        try{
            Files.write(temporary,bytes.toByteArray());checkTarget();
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private void checkTarget()throws IOException{
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)))
            throw new IOException("Game selection file is not a regular local file");
    }
    private Map<String,String> read()throws IOException{
        var entries=new LinkedHashMap<String,String>();checkTarget();
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return entries;
        if(Files.size(file)>MAX_BYTES)throw new IOException("Game selection file is too large");
        byte[] bytes;
        try(var channel=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            var stream=Channels.newInputStream(channel)){
            bytes=stream.readNBytes(MAX_BYTES+1);
        }
        if(bytes.length>MAX_BYTES)throw new IOException("Game selection file grew beyond its byte budget");
        try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(input.readInt()!=MAGIC)throw new IOException("Unknown game selection format");
            int count=input.readUnsignedShort();if(count>MAX_BINDINGS)throw new IOException("Too many saved game selections");
            for(int i=0;i<count;i++){
                String id=input.readUTF(),value=input.readUTF();
                if(!id.matches("[0-9a-f]{64}")||value.isBlank()||value.length()>4096||value.chars().anyMatch(Character::isISOControl)
                        ||!Path.of(value).isAbsolute()||!Path.of(value).normalize().toString().equals(value)||entries.putIfAbsent(id,value)!=null)
                    throw new IOException("Invalid game selection entry");
            }
            if(input.read()!=-1)throw new IOException("Unexpected game selection trailing data");
        }catch(InvalidPathException malformed){throw new IOException("Invalid game selection path",malformed);}
        return entries;
    }
}
