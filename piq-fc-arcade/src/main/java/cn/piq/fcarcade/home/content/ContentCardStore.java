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
    public record Scan(List<Entry> entries,List<Failure> failures) {
        public Scan { entries=List.copyOf(entries);failures=List.copyOf(failures); }
        public String summary(String source){
            return source+"："+entries.size()+" 项可用"+(failures.isEmpty()?"":"，"+failures.size()+" 项被拒绝（状态栏查看原因）");
        }
        /** Existing LIST data field, bounded well below the unchanged packet budget. */
        public byte[] diagnostics(){
            var text=new StringBuilder();
            for(var failure:failures){
                String line=failure+"\n";
                if((text.toString()+line).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>7*1024){
                    text.append("其余失败项请查看服务器日志；没有自动删除或修改原文件。");break;
                }
                text.append(line);
            }
            return text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static String clean(String value,int limit){
        if(value==null||value.isBlank())return "未知读取错误";
        String text=value.replaceAll("[\\p{Cntrl}]"," ");return text.substring(0,Math.min(limit,text.length()));
    }
    public record Entry(String hash,String name,int size) {
        public Entry {
            if(hash==null||!hash.matches("[0-9a-f]{64}")||name==null||name.isBlank()||name.length()>128
                    ||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)
                    ||size<1||size>MAX_BYTES)throw new IllegalArgumentException("Invalid cartridge entry");
        }
    }
    private final Path root;
    private final Set<String> extensions;
    private final Validator validator;
    private final int maxBytes;
    public ContentCardStore(Path root,Set<String> extensions,Validator validator){
        this(root,extensions,validator,DEFAULT_MAX_BYTES);
    }
    public ContentCardStore(Path root,Set<String> extensions,Validator validator,int maxBytes){
        if(maxBytes<1||maxBytes>MAX_BYTES)throw new IllegalArgumentException("Card size budget");
        this.root=root.toAbsolutePath().normalize();this.extensions=Set.copyOf(extensions);this.validator=validator;this.maxBytes=maxBytes;
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
        CabinetGameStore.directory(root);var result=new ArrayList<Entry>();var failures=new ArrayList<Failure>();
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
                    var data=readPath(path);result.add(new Entry(hash(data),path.getFileName().toString(),data.length));
                }
                catch(IOException|RuntimeException failure){failures.add(new Failure(path.getFileName().toString(),failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));}
            }
        }
        return new Scan(result,failures);
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
        if(bytes.length>maxBytes)throw new IOException("ROM 超过此机型大小限制");
        new Entry(hash,name,bytes.length);
        if(!accepts(name)||!hash(bytes).equals(hash))throw new IOException("卡带格式或 SHA256 不匹配");
        validator.validate(bytes);CabinetGameStore.directory(root);
        String stored=hash+name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        var entry=new Entry(hash,stored,bytes.length);Path target=root.resolve(stored);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){read(entry);return entry;}
        long total=0;int count=0;
        try(var files=Files.list(root)){for(var p:files.limit(MAX_FILES+1L).toList()){count++;total+=CabinetGameStore.regular(p).size();}}
        if(count>=MAX_FILES||total+bytes.length>2L*1024*1024*1024)throw new IOException("卡带游戏库容量已满");
        Path temp=root.resolve(".card-"+UUID.randomUUID()+".part");
        try{
            Files.write(temp,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            Files.move(temp,target);read(entry);return entry;
        }finally{Files.deleteIfExists(temp);}
    }
}
