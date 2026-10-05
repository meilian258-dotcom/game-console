// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Immutable identities for a bounded, flat named-content bundle. Not a sharing grant.
 * Inspect and stage belong to an IO/core-owner thread. Sources are never edited or deleted;
 * stage must target a fresh caller-owned workspace, which the caller cleans on any failure. */
public final class LibretroContentFiles {
    public static final int MAX_MAIN=96*1024*1024, MAX_AUXILIARY=64*1024*1024, MAX_TOTAL=128*1024*1024, MAX_FILES=5;
    @FunctionalInterface public interface Check { void run() throws IOException; }
    public record Entry(Path source,int size,String sha256) {}
    private final String mainName;
    private final SortedMap<String,Entry> files;
    private LibretroContentFiles(String mainName,SortedMap<String,Entry> files){this.mainName=mainName;this.files=Collections.unmodifiableSortedMap(files);}
    public String mainName(){return mainName;}
    public Map<String,Entry> files(){return files;}
    public Entry main(){return files.get(mainName);}
    public Map<String,String> auxiliaryHashes(){var hashes=new TreeMap<String,String>();files.forEach((name,file)->{if(!name.equals(mainName))hashes.put(name,file.sha256());});return Map.copyOf(hashes);}
    public long size(){return files.values().stream().mapToLong(Entry::size).sum();}

    public static LibretroContentFiles inspect(String mainName,Map<String,Path> sources,int mainLimit,int auxiliaryLimit,Check check)throws IOException {
        Objects.requireNonNull(check);Objects.requireNonNull(sources);
        if(mainLimit<1||mainLimit>MAX_MAIN||auxiliaryLimit<1||auxiliaryLimit>MAX_AUXILIARY
                ||sources.isEmpty()||sources.size()>MAX_FILES||!sources.containsKey(mainName))throw new IllegalArgumentException("Named content budget");
        var selected=new TreeMap<>(sources);var names=new HashSet<String>();var result=new TreeMap<String,Entry>();long total=0;
        for(var item:selected.entrySet()){
            String name=item.getKey();validateName(name);
            if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate content name");
            Path source=Objects.requireNonNull(item.getValue()).toAbsolutePath().normalize();check.run();var before=regular(source);
            int limit=name.equals(mainName)?mainLimit:auxiliaryLimit;
            if(before.size()<1||before.size()>limit)throw new IOException("Content file exceeds size limit ("+limit+" bytes): "+name);
            if(total>MAX_TOTAL-before.size())throw new IOException("Content bundle exceeds 128 MiB total: "+name);
            total+=before.size();String hash=copyAndHash(source,null,before,check);
            result.put(name,new Entry(source,(int)before.size(),hash));
        }
        return new LibretroContentFiles(mainName,result);
    }
    /** Copy and recheck every captured SHA before returning the exact bundle identity. */
    public byte[] stage(Path root,Check check)throws IOException {
        Objects.requireNonNull(check);Path directory=root.toAbsolutePath().normalize();directory(directory);
        var bytes=new ByteArrayOutputStream();
        try(var identities=new DataOutputStream(bytes)){
            for(var item:files.entrySet()){
                check.run();var expected=item.getValue();var before=regular(expected.source());
                if(before.size()!=expected.size())throw new IOException("Content changed after selection: "+item.getKey());
                Path target=directory.resolve(item.getKey());
                String hash=copyAndHash(expected.source(),target,before,check);
                if(!hash.equals(expected.sha256()))throw new IOException("Content hash changed after selection: "+item.getKey());
                identities.writeUTF(item.getKey());identities.write(HexFormat.of().parseHex(hash));
            }
        }
        check.run();return digest().digest(bytes.toByteArray());
    }
    private static String copyAndHash(Path source,Path target,BasicFileAttributes before,Check check)throws IOException {
        MessageDigest hash=digest();
        try(var in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);
            var out=target==null?OutputStream.nullOutputStream():Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[65536];long copied=0;int n;
            while((n=in.read(buffer))!=-1){check.run();if(n==0)continue;copied+=n;if(copied>before.size())throw new IOException("Content grew during read");hash.update(buffer,0,n);out.write(buffer,0,n);}
            if(copied!=before.size())throw new IOException("Content truncated during read");
        }
        var after=regular(source);
        if(after.size()!=before.size()||!Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Content changed during read");
        return HexFormat.of().formatHex(hash.digest());
    }
    private static BasicFileAttributes regular(Path source)throws IOException {
        directory(source.getParent());var a=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.isSymbolicLink()||a.isOther()||!source.toRealPath().equals(source))throw new IOException("Content must be a regular non-link file");return a;
    }
    private static void directory(Path path)throws IOException {
        for(Path p=path;p!=null;p=p.getParent()){
            var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther()||!p.toRealPath().equals(p))throw new IOException("Content path contains a link or non-directory");
        }
    }
    private static void validateName(String name){
        if(name==null||!name.matches("[a-zA-Z0-9_-]{1,64}\\.[a-zA-Z0-9]{1,10}")
                ||name.split("\\.",2)[0].toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"))throw new IllegalArgumentException("Unsafe content name");
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
