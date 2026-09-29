// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.pvz.runtime;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
/** Owns staging only. Experimental saves never import or overwrite process-mode saves. */
final class PvzJniWorkspace implements AutoCloseable {
    private final Path directory;private final Object identity;
    private FileChannel channel;private FileLock lock;
    Path data,core,save;
    PvzJniWorkspace(Path game,Path content,UUID player)throws Exception {
        Objects.requireNonNull(player);
        if(!content.isAbsolute())throw new IOException("请选择本机main.pak完整路径");
        content=cn.piq.retro.storage.ConsoleStorage.rebind(game,content);
        if(!content.getFileName().toString().equalsIgnoreCase("main.pak")||!Files.isRegularFile(content,LinkOption.NOFOLLOW_LINKS))throw new IOException("请选择本机main.pak文件");
        directory=Files.createTempDirectory("piq-pvz-jni-game-").toAbsolutePath().normalize();
        identity=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        try{
            data=Files.createDirectory(directory.resolve("data"));
            PvzRuntime.copyBounded(content,data.resolve("main.pak"),64L*1024*1024);
            String hash=PvzRuntime.sha(data.resolve("main.pak"));
            Path props=content.getParent().resolve("properties");
            if(Files.isDirectory(props,LinkOption.NOFOLLOW_LINKS)){
                Path to=Files.createDirectory(data.resolve("properties"));
                for(String n:List.of("default.xml","Layout.xml","partner.xml","partner.xml.sig","partner_logo.jpg"))if(Files.isRegularFile(props.resolve(n),LinkOption.NOFOLLOW_LINKS))PvzRuntime.copyBounded(props.resolve(n),to.resolve(n),4L*1024*1024);
            }
            save=savePath(cn.piq.retro.storage.ConsoleStorage.root(game.toAbsolutePath()),player,hash);
            checkNativePath(save);checkNativePath(data);
            noLinks(save);Files.createDirectories(save);noLinks(save);
            Files.createDirectories(save.resolve("PvZ-Portable"));noLinks(save.resolve("PvZ-Portable"));
            channel=FileChannel.open(save.resolve("session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try{lock=channel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("JNI试验存档正在使用",e);}
            if(lock==null)throw new IOException("JNI试验存档正在使用");
            core=directory.resolve("pvz_libretro.dll");
            try(var in=PvzRuntime.class.getResourceAsStream("/core/pvz/pvz_libretro.dll")){if(in==null)throw new IOException("缺少内置PvZ核心");Files.copy(in,core);}
            if(!PvzRuntime.sha(core).equals(PvzRuntime.CORE_SHA))throw new IOException("内置PvZ核心校验失败");
        }catch(Exception e){try{close();}catch(Exception cleanup){e.addSuppressed(cleanup);}throw e;}
    }
    static Path savePath(Path storage,UUID player,String hash){
        if(hash==null||!hash.matches("[0-9A-F]{64}"))throw new IllegalArgumentException("Invalid content identity");
        try{String identity=java.util.HexFormat.of().withUpperCase().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((player+":"+hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return storage.resolve("piq-pvz/jni-common-v1-saves").resolve(identity);
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void checkNativePath(Path path)throws IOException{if(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>160)throw new IOException("JNI试验目录过长（上限160 UTF-8字节），请选择较短的游戏实例路径或切回独立进程");}
    private static void noLinks(Path path)throws IOException{
        for(Path p=path.toAbsolutePath();p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
            var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther())throw new IOException("JNI保存目录不能经过链接或非目录");
        }
    }
    @Override public void close()throws IOException {
        if(lock!=null){lock.release();lock=null;}if(channel!=null){channel.close();channel=null;}
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        var a=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||a.isSymbolicLink()||!Objects.equals(identity,a.fileKey()))throw new IOException("JNI临时目录身份改变，保留现场");
        // Only the exact generated root, no FOLLOW_LINKS; inspect every entry before deleting.
        try(var walk=Files.walk(directory)){
            var entries=walk.toList();
            for(Path p:entries){if(!p.toAbsolutePath().normalize().startsWith(directory))throw new IOException("Cleanup escaped staging");var stat=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(stat.isSymbolicLink()||stat.isOther())throw new IOException("JNI临时目录含链接，保留现场");}
            for(Path p:entries.stream().sorted(Comparator.reverseOrder()).toList())Files.delete(p);
        }
    }
}
