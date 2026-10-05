package cn.piq.nativearcade.client;

import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.netplay.NetplayProfile;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.*;

/** General arcade Netplay with a trusted core, not an allowlist of game hashes.
 * Reads only the selected ZIP and declared adjacent BIOS, without scanning.
 * FBNeo is separately licensed. The legacy MAME snapshot backend is unchanged.
 */
public final class NativeNetplayContent {
    public static final String CORE_SHA=cn.piq.nativearcade.NativeNetplayProfile.CORE_SHA;
    public static final String CORE_RESOURCE=cn.piq.nativearcade.NativeNetplayProfile.CORE_RESOURCE;
    private NativeNetplayContent(){}
    /** JNI-only bounded named files. No ROM-sized Java arrays, no implicit content permission. */
    public static CabinetBackend.NetplayContent loadFiles(Path rom)throws IOException{
        Path source=rom.toAbsolutePath().normalize();String name=source.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!validGameName(name))throw new IOException("请选择原名的街机 ZIP（例如 dino.zip / kov.zip），不要选择 BIOS");
        var paths=new TreeMap<String,Path>();paths.put(name,source);
        for(String companion:new TreeSet<>(CabinetGameManifest.BIOS)){
            Path path=source.getParent().resolve(companion);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))paths.put(companion,path);
        }
        var files=cn.piq.retro.libretro.LibretroContentFiles.inspect(name,paths,
                cn.piq.retro.libretro.LibretroContentFiles.MAX_MAIN,16*1024*1024,()->{if(Thread.currentThread().isInterrupted())throw new IOException("游戏校验已取消");});
        for(var entry:files.files().entrySet()){
            if(entry.getValue().size()<22)throw new IOException("街机 ZIP 至少需要 22 字节："+entry.getKey());
            byte[] magic;try(var in=Files.newInputStream(entry.getValue().source(),LinkOption.NOFOLLOW_LINKS)){magic=in.readNBytes(4);}
            if(magic.length!=4||magic[0]!='P'||magic[1]!='K'||magic[2]!=3||magic[3]!=4)throw new IOException("不是有效的街机 ZIP："+entry.getKey());
        }
        return new CabinetBackend.NetplayContent(profile(name),files);
    }
    public static CabinetBackend.NetplayContent load(Path rom)throws IOException{
        Path source=rom.toAbsolutePath().normalize();
        String name=source.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!validGameName(name))throw new IOException("请选择原名的街机 ZIP（例如 dino.zip / kov.zip），不要选择 BIOS");
        byte[] game=read(source,64*1024*1024);
        Map<String,byte[]> extras=new LinkedHashMap<>();long total=game.length;
        for(String companion:new TreeSet<>(CabinetGameManifest.BIOS)){
            Path path=source.getParent().resolve(companion);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            byte[] bytes=read(path,16*1024*1024);total+=bytes.length;
            if(total>CabinetGameManifest.MAX_TOTAL)throw new IOException("游戏与 BIOS 合计不能超过 128 MiB");
            extras.put(companion,bytes);
        }
        return new CabinetBackend.NetplayContent(profile(name),game,Map.copyOf(extras));
    }
    public static NetplayProfile profile(String name){
        if(!validGameName(name))throw new IllegalArgumentException("Invalid arcade ZIP name");
        return cn.piq.nativearcade.NativeNetplayProfile.profile(name);
    }
    static boolean validGameName(String name){
        return name!=null&&name.matches("[a-z0-9_]{1,32}\\.zip")&&!CabinetGameManifest.BIOS.contains(name)
            &&!name.substring(0,name.length()-4).toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    }
    private static byte[] read(Path path,int limit)throws IOException{
        for(Path parent=path.getParent();parent!=null;parent=parent.getParent()){
            var a=Files.readAttributes(parent,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther())throw new IOException("游戏目录不能是链接");
        }
        var before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.isSymbolicLink()||before.isOther()||before.size()<22||before.size()>limit)throw new IOException("ZIP 文件类型或大小不符合要求："+path.getFileName());
        byte[] bytes;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes((int)before.size()+1);}
        var after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes.length!=before.size()||after.size()!=before.size()||!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime())||!after.isRegularFile()||after.isSymbolicLink()||after.isOther())throw new IOException("读取时文件发生变化："+path.getFileName());
        if(bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)throw new IOException("不是有效的街机 ZIP："+path.getFileName());
        return bytes;
    }
}
