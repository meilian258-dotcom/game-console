// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.retro.libretro;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** One trusted adapter's native-owned NVRAM file, never a game-state snapshot or network path. */
public final class LibretroOperatingFile {
    public interface Sink { void closed(byte[] bytes) throws Exception; }
    private final String relative;
    private final int maximum;
    private final byte[] initial;
    private final Sink sink;
    public LibretroOperatingFile(String relative,int maximum,byte[] initial,Sink sink) {
        if(relative==null||!relative.matches("[a-z0-9_-]{1,32}/[a-z0-9_-]{1,32}\\.fs")
                ||Arrays.stream(relative.split("[/\\.]",-1)).anyMatch(s->s.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))
                ||maximum<1||maximum>1024*1024||initial!=null&&(initial.length<1||initial.length>maximum))
            throw new IllegalArgumentException("Invalid trusted operating file");
        this.relative=relative;this.maximum=maximum;this.initial=initial==null?null:initial.clone();this.sink=Objects.requireNonNull(sink);
    }
    public void prepare(Path root)throws IOException {
        Path target=target(root);check(root,true);Files.createDirectories(target.getParent());check(target.getParent(),true);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Operating file already exists in fresh workspace");
        if(initial!=null)Files.write(target,initial,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
    }
    /** Called only after the native owner confirms unload; never while native code may write. */
    public void closed(Path root)throws Exception {
        Path target=target(root);check(root,true);check(target.getParent(),true);
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return; // No NVRAM: do not erase the prior server copy.
        check(target,false);var before=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()<1||before.size()>maximum)throw new IOException("Operating file size rejected; previous settings retained");
        byte[] bytes;try(var input=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(maximum+1);}
        var after=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes.length!=before.size()||after.size()!=before.size()||!Objects.equals(before.fileKey(),after.fileKey())
                ||!before.lastModifiedTime().equals(after.lastModifiedTime())||!after.isRegularFile()||after.isOther()||after.isSymbolicLink())
            throw new IOException("Operating file changed after native close");
        sink.closed(bytes);
    }
    private Path target(Path root)throws IOException {Path base=root.toAbsolutePath().normalize();Path target=base.resolve(relative).normalize();if(!target.startsWith(base))throw new IOException("Operating file escaped workspace");return target;}
    private static void check(Path path,boolean directory)throws IOException {
        for(Path p=path.toAbsolutePath().normalize();p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
            var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()||(p.equals(path.toAbsolutePath().normalize())&&!directory?!a.isRegularFile():!a.isDirectory()))throw new IOException("Operating file path must not use links");
        }
    }
}
