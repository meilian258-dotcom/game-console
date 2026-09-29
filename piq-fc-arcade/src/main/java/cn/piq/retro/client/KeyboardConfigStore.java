package cn.piq.retro.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Explicit-save-only local file with bounded reads, no reparse traversal and revision checks. */
public final class KeyboardConfigStore {
    private KeyboardConfigStore(){}
    public record Loaded(KeyboardConfig config,String revision,String warning){}
    public static Loaded load(Path gameDir)throws IOException{
        return load(gameDir,null,null);
    }
    /** Resolve old default hotkeys only after the client supplies live, unmodified legacy keys. */
    public static Loaded load(Path gameDir,Map<KeyboardConfig.Profile,int[][]> legacy,Map<KeyboardConfig.Profile,int[]> extras)throws IOException{
        byte[] bytes=read(path(gameDir));if(bytes==null)return new Loaded(KeyboardConfig.defaults(),"missing","");
        try{var decoded=decodeResult(bytes,legacy,extras);return new Loaded(decoded.config(),hash(bytes),decoded.warning());}
        catch(IllegalArgumentException|NullPointerException bad){return new Loaded(KeyboardConfig.defaults(),hash(bytes),"键盘配置无效，暂用 WASD + JKL/IOP；N 锁位置，保存后才替换文件");}
    }
    public static Loaded save(Path gameDir,KeyboardConfig config,String revision)throws IOException{
        Path path=path(gameDir);byte[] before=read(path);if(!Objects.equals(revision,before==null?"missing":hash(before)))throw new IOException("配置已变化，请重新打开设置");
        Path parent=path.getParent();if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(parent);validate(parent);
        byte[] bytes=encode(config);Path temp=Files.createTempFile(parent,"piq-keyboard-",".tmp");
        try{Files.write(temp,bytes,StandardOpenOption.TRUNCATE_EXISTING);validate(path);if(!Arrays.equals(before,read(path)))throw new IOException("配置保存前发生变化");
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){throw new IOException("不支持原子保存，原配置未改",e);}
            return new Loaded(config,hash(bytes),"");
        }finally{Files.deleteIfExists(temp);}
    }
    public static Path path(Path gameDir)throws IOException{Path dir=gameDir.toAbsolutePath().normalize();validate(dir);if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("游戏目录不存在");Path p=dir.resolve("config/piq-keyboard.properties");validate(p);return p;}
    private static void validate(Path path)throws IOException{Path cursor=path.getRoot();for(Path component:path){cursor=cursor.resolve(component);if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))continue;var a=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(a.isSymbolicLink()||a.isOther())throw new IOException("键盘配置路径不能包含链接或特殊文件");}}
    private static byte[] read(Path path)throws IOException{validate(path);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("键盘配置不是普通文件");try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("键盘配置超过64KiB");return bytes;}}
    static byte[] encode(KeyboardConfig c)throws IOException{Properties p=new Properties();p.setProperty("version","3");p.setProperty("toggle",Integer.toString(c.toggleKey()));p.setProperty("settings",Integer.toString(c.settingsKey()));for(var profile:KeyboardConfig.Profile.values()){var b=c.bindings(profile);p.setProperty(profile+".preset",b.preset().name());p.setProperty(profile+".custom",String.join(",",b.customKeys().stream().map(String::valueOf).toList()));}StringWriter s=new StringWriter();p.store(s,"PIQ local keyboard controls");return s.toString().getBytes(StandardCharsets.UTF_8);}
    private static Properties properties(byte[] bytes)throws IOException{Properties p=new Properties();p.load(new StringReader(new String(bytes,StandardCharsets.UTF_8)));return p;}
    private record Decoded(KeyboardConfig config,String warning){}
    static KeyboardConfig decode(byte[] bytes)throws IOException{return decodeResult(bytes).config();}
    private static Decoded decodeResult(byte[] bytes)throws IOException{return decodeResult(bytes,null,null);}
    private static Decoded decodeResult(byte[] bytes,Map<KeyboardConfig.Profile,int[][]> legacy,Map<KeyboardConfig.Profile,int[]> extras)throws IOException{
        Properties p=properties(bytes);String version=p.getProperty("version");
        if(!Set.of("1","2","3").contains(version))throw new IllegalArgumentException("Unknown keyboard config");
        int toggle=Integer.parseInt(p.getProperty("toggle")),settings=Integer.parseInt(p.getProperty("settings"));
        var map=new EnumMap<KeyboardConfig.Profile,KeyboardConfig.Bindings>(KeyboardConfig.Profile.class);
        for(var profile:KeyboardConfig.Profile.values()){
            var keys=new ArrayList<>(Arrays.stream(p.getProperty(profile+".custom").split(",",-1)).map(Integer::valueOf).toList());
            var preset=KeyboardConfig.Preset.valueOf(p.getProperty(profile+".preset"));
            map.put(profile,new KeyboardConfig.Bindings(preset,keys));
        }
        var config=new KeyboardConfig(map,toggle,settings);
        if("3".equals(version))return new Decoded(config,"");
        // Old formats did not record whether a default-looking hotkey was explicitly
        // selected. Only migrate a recognizable fixed-layout/default-hotkey setup.
        // Custom mappings are never rewritten. Live defaults require actual raw-key checks.
        boolean oldDefault=(toggle==90||("1".equals(version)&&toggle==297))
                &&settings==KeyboardConfig.DEFAULT_SETTINGS_KEY;
        boolean fixed=map.values().stream().noneMatch(b->b.preset()==KeyboardConfig.Preset.CUSTOM||b.preset()==KeyboardConfig.Preset.LEGACY);
        if(oldDefault&&fixed)return new Decoded(config.withHotkeys(KeyboardConfig.DEFAULT_TOGGLE_KEY,settings),
                "旧默认位置锁已在内存中改为 N；游戏键保持不变，保存后才更新文件");
        if(oldDefault&&map.values().stream().noneMatch(b->b.preset()==KeyboardConfig.Preset.CUSTOM)&&legacy!=null&&extras!=null){
            for(var profile:KeyboardConfig.Profile.values())if(map.get(profile).preset()==KeyboardConfig.Preset.LEGACY){
                int[][] raw=legacy.get(profile);int[] extra=extras.get(profile);
                if(raw==null||extra==null||raw.length!=profile.bits)return new Decoded(config,"旧默认位置锁暂未迁移：实际游戏绑定未就绪；请打开键位设置检查 N");
                for(int[] row:raw){if(row==null)return new Decoded(config,"旧默认位置锁暂未迁移：实际游戏绑定未就绪；请打开键位设置检查 N");
                    for(int key:row)if(key==KeyboardConfig.DEFAULT_TOGGLE_KEY)return occupied(config);}
                for(int key:extra)if(key==KeyboardConfig.DEFAULT_TOGGLE_KEY)return occupied(config);
            }
            return new Decoded(config.withHotkeys(KeyboardConfig.DEFAULT_TOGGLE_KEY,settings),
                    "旧默认位置锁已改为 N；已检查实际旧游戏键及附加功能键，未挪用 Z/X 作为锁键；保存后才更新文件");
        }
        return new Decoded(config,"旧配置的自定义/跟随绑定与快捷键已保留；新默认位置锁为 N，可在下方改键并保存");
    }
    private static Decoded occupied(KeyboardConfig config){return new Decoded(config,"未改位置锁：N 已被旧游戏功能使用，原锁键及全部游戏绑定已保留；请打开键位设置自行选择");}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
