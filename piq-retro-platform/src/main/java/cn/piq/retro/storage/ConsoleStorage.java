package cn.piq.retro.storage;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Shared per-instance/per-world data root. Call with the ORIGINAL instance or world directory.
 * Known component names stay unchanged beneath game-console so offline archive entry names
 * and artifact hashes remain valid. Never relocates mods/config/natives or arbitrary external files.
 */
public final class ConsoleStorage {
    private static final List<String> COMPONENTS=List.of("piq-fc","piq-cabinet","piq-sfc-home","piq-native-arcade","piq-gba","piq-pvz","piq-flash-box","piq-computer","piq-runtime-packs","piq-private-home");
    private static final Set<Path> READY=new HashSet<>();
    private ConsoleStorage(){}
    public static Path location(Path base){return Objects.requireNonNull(base).toAbsolutePath().normalize().resolve("game-console");}

    public static synchronized Path root(Path base){
        Path original=Objects.requireNonNull(base).toAbsolutePath().normalize();
        Path target=original.resolve("game-console");
        if(READY.contains(original))return target;
        try{
            if(!Files.isDirectory(original,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(original);
            original=original.toRealPath();target=original.resolve("game-console");
            directory(original,target);
            for(String name:COMPONENTS)migrate(original,target,name);
            READY.add(base.toAbsolutePath().normalize());return target;
        }catch(IOException e){throw new UncheckedIOException("方块电玩目录迁移未完成；原文件保留，勿启动空档："+e.getMessage(),e);}
    }
    /** Rebind only old paths owned by this mod, after migration has succeeded. */
    public static Path rebind(Path base,Path configured){
        Path original=base.toAbsolutePath().normalize(),file=configured.toAbsolutePath().normalize();
        if(!file.startsWith(original))return configured;
        Path relative=original.relativize(file);
        if(relative.getNameCount()==0||!COMPONENTS.contains(relative.getName(0).toString()))return configured;
        Path rebound=root(original).resolve(relative);
        return Files.exists(rebound,LinkOption.NOFOLLOW_LINKS)?rebound:configured;
    }
    private static void migrate(Path base,Path target,String name)throws IOException{
        Path old=base.resolve(name),dest=target.resolve(name);
        if(!Files.exists(old,LinkOption.NOFOLLOW_LINKS))return;
        checked(base,old,true);directory(base,dest);
        Path meta=target.resolve(".migration");directory(base,meta);
        Path done=meta.resolve(name+".done");
        // An older mod was run after migration. Never resurrect its stale progress.
        if(Files.exists(done,LinkOption.NOFOLLOW_LINKS))throw new IOException("检测到旧版本重新创建目录："+old+"；请核对后手动归档，未覆盖新数据");
        List<Path> files=new ArrayList<>(),dirs=new ArrayList<>();
        Files.walkFileTree(old,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{checked(base,dir,true);dirs.add(old.relativize(dir));return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{checked(base,file,false);files.add(old.relativize(file));return FileVisitResult.CONTINUE;}
        });
        // Preflight every conflict before any existing destination content is changed.
        for(Path relative:dirs){Path d=dest.resolve(relative);if(Files.exists(d,LinkOption.NOFOLLOW_LINKS))checked(base,d,true);}
        for(Path relative:files){Path d=dest.resolve(relative);if(Files.exists(d,LinkOption.NOFOLLOW_LINKS)){checked(base,d,false);same(old.resolve(relative),d);}}
        for(Path relative:dirs)directory(base,dest.resolve(relative));
        for(Path relative:files){Path s=old.resolve(relative),d=dest.resolve(relative);checked(base,s,false);if(!Files.exists(d,LinkOption.NOFOLLOW_LINKS)){
            Path temp=Files.createTempFile(d.getParent(),".migration-",".part");
            Files.copy(s,temp,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);same(s,temp);
            Files.move(temp,d);
        }same(s,d);}
        // Verify the full source inventory again before recoverable archival.
        try(var paths=Files.walk(old)){
            Set<Path> now=new HashSet<>();for(Path p:paths.toList()){if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))checked(base,p,true);else{checked(base,p,false);now.add(old.relativize(p));same(p,dest.resolve(old.relativize(p)));}}
            if(!now.equals(new HashSet<>(files)))throw new IOException("迁移时源目录发生变化："+old);
        }
        Path backups=target.resolve("legacy-backup");directory(base,backups);
        Path backup=backups.resolve(name+"-"+UUID.randomUUID());
        if(!old.getParent().equals(base)||!backup.getParent().equals(backups))throw new IOException("非法归档目标");
        checked(base,old,true);Files.move(old,backup,StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(done,"Copied and byte-verified; original preserved at "+backup+System.lineSeparator(),StandardOpenOption.CREATE_NEW);
    }
    private static void same(Path a,Path b)throws IOException{if(Files.mismatch(a,b)!=-1)throw new IOException("迁移冲突/校验不一致（未覆盖）："+a+" -> "+b);}
    private static void directory(Path base,Path path)throws IOException{
        if(!path.normalize().startsWith(base))throw new IOException("目录越界："+path);
        Path current=base;
        for(Path p:base.relativize(path)){current=current.resolve(p);if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(current);checked(base,current,true);}
    }
    private static void checked(Path base,Path path,boolean dir)throws IOException{
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(a.isSymbolicLink()||a.isOther()||(dir?!a.isDirectory():!a.isRegularFile())||!path.toRealPath().equals(path.toAbsolutePath().normalize())||!path.toRealPath().startsWith(base))throw new IOException("拒绝链接或非普通路径："+path);
    }
}
