// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.CartridgeCoverRepository;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Reuses FC's bounded, hash-addressed PNG store; rejects reparse parents before every access. */
public final class SfcCoverStore {
    private final Path root;
    private final CartridgeCoverRepository store;
    public SfcCoverStore(Path root){this.root=root.toAbsolutePath().normalize();store=new CartridgeCoverRepository(this.root);}
    public byte[] read(String hash)throws IOException{check(false);return store.read(hash);}
    public java.util.List<String> list()throws IOException{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return java.util.List.of();check(false);return store.list();}
    public void store(String hash,byte[] png)throws IOException{check(true);store.store(hash,png);check(false);}
    private void check(boolean create)throws IOException{
        Path cursor=root.getRoot();
        for(Path part:root){cursor=cursor.resolve(part);
            if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)){if(!create)throw new IOException("Cover directory missing");try{Files.createDirectory(cursor);}catch(FileAlreadyExistsException ignored){}}
            BasicFileAttributes attrs=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isDirectory()||attrs.isSymbolicLink()||attrs.isOther()||!cursor.toRealPath().equals(cursor))throw new IOException("Unsafe cover directory");
        }
    }
}
