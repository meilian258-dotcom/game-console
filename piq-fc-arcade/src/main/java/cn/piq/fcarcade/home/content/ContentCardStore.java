package cn.piq.fcarcade.home.content;

import cn.piq.fcarcade.cabinet.CabinetGameStore;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Ordinary cartridge files, immutable writes and bounded scans. IO-worker only. */
public final class ContentCardStore {
    public static final int DEFAULT_MAX_BYTES=8*1024*1024, MAX_BYTES=32*1024*1024, MAX_FILES=256, CHUNK=cn.piq.fcarcade.home.CartridgeLimits.CHUNK_BYTES;
    @FunctionalInterface public interface Validator { void validate(byte[] bytes) throws IOException; }
    public record Failure(String name,String reason) {
        public Failure { name=clean(name,128);reason=clean(reason,160); }
        @Override public String toString(){return name+"："+reason;}
    }
    /** A rejected file is never a selectable Entry; the remaining valid files survive. */
    public record Scan(List<Entry> entries,List<Failure> failures,List<Failure> warnings) {
        public Scan(List<Entry> entries,List<Failure> failures){this(entries,failures,List.of());}
        public Scan { entries=List.copyOf(entries);failures=List.copyOf(failures);warnings=List.copyOf(warnings); }
        public String summary(String source){
            return new ContentScanReport<>(entries,failures,warnings).summary(source);
        }
        /** Existing LIST data field, bounded well below the unchanged packet budget. */
        public byte[] diagnostics(){
            return new ContentScanReport<>(entries,failures,warnings).diagnostics();
        }
    }
    private static String clean(String value,int limit){
        if(value==null||value.isBlank())return "未知读取错误";
        String text=value.replaceAll("[\\p{Cntrl}]"," ");return text.substring(0,Math.min(limit,text.length()));
    }
    public record Entry(String hash,String name,int size,String displayName) {
        public Entry(String hash,String name,int size){this(hash,name,size,ContentCardNames.fallback(name));}
        public Entry {
            if(hash==null||!hash.matches("[0-9a-f]{64}")||name==null||name.isBlank()||name.length()>128
                    ||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)
                    ||size<1||size>MAX_BYTES)throw new IllegalArgumentException("Invalid cartridge entry");
            ContentCardNames.validate(displayName);
        }
        /** Card NBT and old callers carry only physical identity, never mutable presentation metadata. */
        @Override public boolean equals(Object other){return other instanceof Entry e&&hash.equals(e.hash)&&name.equals(e.name)&&size==e.size;}
        @Override public int hashCode(){return Objects.hash(hash,name,size);}
    }
    private static final Object[] WRITE_LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private final Path root;
    private final Set<String> extensions;
    private final Validator validator;
    private final int maxBytes;
    private final ContentCardNames names;
    public ContentCardStore(Path root,Set<String> extensions,Validator validator){
        this(root,extensions,validator,DEFAULT_MAX_BYTES);
    }
    public ContentCardStore(Path root,Set<String> extensions,Validator validator,int maxBytes){
        this(root,extensions,validator,maxBytes,null);
    }
    /** Explicit server catalog metadata; local scans and runtime caches do not create metadata. */
    public ContentCardStore(Path root,Set<String> extensions,Validator validator,int maxBytes,Path metadata){
        if(maxBytes<1||maxBytes>MAX_BYTES)throw new IllegalArgumentException("Card size budget");
        this.root=root.toAbsolutePath().normalize();this.extensions=Set.copyOf(extensions);this.validator=validator;this.maxBytes=maxBytes;
        if(metadata!=null&&metadata.toAbsolutePath().normalize().startsWith(this.root))throw new IllegalArgumentException("Content metadata must be separate from ROM files");
        this.names=metadata==null?null:new ContentCardNames(metadata);
    }
    public boolean accepts(String name){int dot=name.lastIndexOf('.');return dot>=0&&extensions.contains(name.substring(dot+1).toLowerCase(Locale.ROOT));}
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public byte[] readPath(Path path)throws IOException{
        if(!accepts(path.getFileName().toString()))throw new IOException("卡带文件格式不支持");
        var before=CabinetGameStore.regular(path);
        if(before.size()<1||before.size()>maxBytes)throw new IOException("ROM 不能超过 "+maxBytes/1024/1024+" MiB");
        byte[] bytes;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(maxBytes+1);}
        var after=CabinetGameStore.regular(path);
        if(bytes.length!=before.size()||after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())
                ||!Objects.equals(before.fileKey(),after.fileKey()))throw new IOException("ROM 读取期间发生变化");
        validator.validate(bytes);return bytes;
    }
    public List<Entry> list()throws IOException{
        return scan().entries();
    }
    public Scan scan()throws IOException{
        CabinetGameStore.directory(root);var result=new ArrayList<Entry>();var failures=new ArrayList<Failure>();var warnings=new ArrayList<Failure>();
        try(var files=Files.list(root)){
            var paths=files.limit(MAX_FILES+1L).toList();
            if(paths.size()>MAX_FILES)throw new IOException("卡带目录超过 256 个文件，请管理员整理");
            for(Path path:paths.stream().sorted().toList()){
                try{
                    if(!accepts(path.getFileName().toString())){
                        CabinetGameStore.regular(path);
                        failures.add(new Failure(path.getFileName().toString(),"未扫描：扩展名不支持（支持 "+extensions.stream().sorted().map(ext->"."+ext).collect(java.util.stream.Collectors.joining(" / "))+"）"));
                        continue;
                    }
                    var data=readPath(path);var entry=new Entry(hash(data),path.getFileName().toString(),data.length);
                    if(names!=null){
                        try{String display=names.read(entry.hash());if(display!=null)entry=new Entry(entry.hash(),entry.name(),entry.size(),display);}
                        catch(IOException failure){warnings.add(new Failure(entry.name(),"名称读取失败，保留原内容："+failure.getMessage()));}
                    }
                    result.add(entry);
                }
                catch(IOException|RuntimeException failure){failures.add(new Failure(path.getFileName().toString(),failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));}
            }
        }
        return new Scan(result,failures,warnings);
    }
    public byte[] read(Entry entry)throws IOException{
        CabinetGameStore.requireDirectory(root);
        var path=root.resolve(entry.name());
        if(!path.getParent().equals(root))throw new IOException("卡带路径无效");
        var bytes=readPath(path);
        if(bytes.length!=entry.size()||!hash(bytes).equals(entry.hash()))throw new IOException("ROM 内容已改变，请重新写卡");
        return bytes;
    }
    public Entry store(String name,String hash,byte[] bytes)throws IOException{
        synchronized(WRITE_LOCKS[Math.floorMod(root.hashCode(),WRITE_LOCKS.length)]){return storeLocked(name,hash,bytes);}
    }
    private Entry storeLocked(String name,String hash,byte[] bytes)throws IOException{
        if(bytes.length>maxBytes)throw new IOException("ROM 超过此机型大小限制");
        new Entry(hash,name,bytes.length);
        if(!accepts(name)||!hash(bytes).equals(hash))throw new IOException("卡带格式或 SHA256 不匹配");
        validator.validate(bytes);CabinetGameStore.directory(root);
        String stored=hash+name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        var entry=new Entry(hash,stored,bytes.length);Path target=root.resolve(stored);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){read(entry);return named(entry,name);}
        long total=0;int count=0;
        try(var files=Files.list(root)){for(var p:files.limit(MAX_FILES+1L).toList()){count++;total+=CabinetGameStore.regular(p).size();}}
        if(count>=MAX_FILES||total+bytes.length>2L*1024*1024*1024)throw new IOException("卡带游戏库容量已满");
        Path temp=root.resolve(".card-"+UUID.randomUUID()+".part");
        try{
            Files.write(temp,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            Files.move(temp,target);read(entry);return named(entry,name);
        }finally{Files.deleteIfExists(temp);}
    }
    private Entry named(Entry entry,String originalName)throws IOException{
        return names==null?entry:new Entry(entry.hash(),entry.name(),entry.size(),names.remember(entry.hash(),originalName));
    }
}
