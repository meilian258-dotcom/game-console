// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeSnapshotProfile;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Fixed opaque ROM/BIOS copies in one owned ASCII directory; never writes source files or user saves. */
final class NativeSnapshotWorkspace implements AutoCloseable {
    private final Path root;private final Object identity;private final String game,romHash;
    NativeSnapshotWorkspace(Path rom)throws IOException{
        game=rom.getFileName().toString();romHash=NativeSnapshotProfile.ROMS.get(game);
        if(romHash==null)throw new IOException("本地输入同步仅支持已验证版本的 kof97.zip / mslug2.zip；其他游戏请使用音画串流");
        root=Files.createTempDirectory("piq-native-snapshot-owned-").toAbsolutePath().normalize();
        identity=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(!root.toString().matches("[\\x20-\\x7e]+")||root.toString().contains("\"")||root.toString().contains(";")){
            close();throw new IOException("本地同步需要不含特殊字符的英文临时目录");
        }
        regular(root,true);
    }
    Path directory(){return root;}String game(){return game;}String romHash(){return romHash;}
    Path stage(Path rom,BooleanSupplier cancelled)throws IOException{
        verifyRoot();rom=rom.toAbsolutePath().normalize();
        copy(rom,root.resolve(game),romHash,NativeSnapshotProfile.ROM_BYTES.get(game),cancelled);
        copy(rom.getParent().resolve("neogeo.zip"),root.resolve("neogeo.zip"),NativeSnapshotProfile.BIOS_SHA,NativeSnapshotProfile.BIOS_BYTES,cancelled);
        verifyRoot();return root.resolve(game);
    }
    private static void copy(Path from,Path to,String sha,long bytes,BooleanSupplier cancelled)throws IOException{
        var before=verify(from,sha,bytes,cancelled);
        try(var in=Files.newInputStream(from,LinkOption.NOFOLLOW_LINKS);var out=Files.newOutputStream(to,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[131072];long total=0;for(int n;(n=in.read(buffer))!=-1;){
                cancellation(cancelled);total+=n;if(total>bytes)throw new IOException("Source grew during staging");out.write(buffer,0,n);
            }
            if(total!=bytes)throw new IOException("Source size changed during staging");
        }
        verify(to,sha,bytes,cancelled);var after=verify(from,sha,bytes,cancelled);
        if(!same(before,after))throw new IOException("ROM/BIOS changed while making private copy");
    }
    static BasicFileAttributes verify(Path file,String sha,long bytes,BooleanSupplier cancelled)throws IOException{
        cancellation(cancelled);file=file.toAbsolutePath().normalize();regular(file,false);
        var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()!=bytes)throw new IOException("Fixed runtime/ROM size mismatch: "+file.getFileName());
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[131072];for(int n;(n=in.read(buffer))!=-1;){
                cancellation(cancelled);count+=n;if(count>bytes)throw new IOException("File grew during verification");digest.update(buffer,0,n);
            }}
            var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);regular(file,false);
            if(count!=bytes||!same(before,after)||!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha))throw new IOException("固定运行库或 ROM/BIOS 摘要不匹配："+file.getFileName());
            return after;
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){return a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&Objects.equals(a.fileKey(),b.fileKey());}
    static void cancellation(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean())throw new IOException("Native snapshot startup cancelled");}
    private static void regular(Path path,boolean directory)throws IOException{
        for(Path p=path;p!=null;p=p.getParent()){
            var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()||(p.equals(path)&&!directory?!a.isRegularFile():!a.isDirectory()))throw new IOException("Snapshot paths must be regular and non-linked");
        }
    }
    private void verifyRoot()throws IOException{
        regular(root,true);var now=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!Objects.equals(identity,now.fileKey()))throw new IOException("Private snapshot directory identity changed");
    }
    /** Only after the exact child exited; no FOLLOW_LINKS and no path outside this owned directory. */
    @Override public void close()throws IOException{
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;verifyRoot();
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            private void guard(Path p)throws IOException{if(!p.toAbsolutePath().normalize().startsWith(root))throw new IOException("Cleanup escaped owned snapshot directory");}
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException{guard(dir);if(a.isSymbolicLink()||a.isOther())throw new IOException("Refusing redirected snapshot cleanup");return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException{guard(file);Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;guard(dir);Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
}
