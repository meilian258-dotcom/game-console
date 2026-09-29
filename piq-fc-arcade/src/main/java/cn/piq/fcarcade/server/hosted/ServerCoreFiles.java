package cn.piq.fcarcade.server.hosted;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Fixed, bounded, non-link filesystem access used only on core starter/owner threads. */
public final class ServerCoreFiles {
    private ServerCoreFiles(){}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static String resourceHash(Class<?> source,String name,int maximum)throws IOException{
        try(var input=source.getResourceAsStream(name)){
            if(input==null)throw new IOException("Hosted core resource missing: "+name);
            byte[] bytes=input.readNBytes(maximum+1);if(bytes.length<1||bytes.length>maximum)throw new IOException("Hosted core resource bounds");return sha256(bytes);
        }
    }
    public static void directory(Path path,boolean create)throws IOException{
        Path normalized=path.toAbsolutePath().normalize(),cursor=normalized.getRoot();
        for(Path part:normalized){cursor=cursor.resolve(part);
            if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS)){if(!create)throw new IOException("Directory missing");Files.createDirectory(cursor);}
            var a=Files.readAttributes(cursor,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isDirectory()||a.isSymbolicLink()||a.isOther()||!cursor.toRealPath().equals(cursor))throw new IOException("Redirected directory rejected");
        }
    }
    public static byte[] read(Path path,int minimum,int maximum)throws IOException{
        Path file=path.toAbsolutePath().normalize();directory(file.getParent(),false);
        var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.isSymbolicLink()||before.isOther()||before.size()<minimum||before.size()>maximum)throw new IOException("Hosted file bound: "+file.getFileName());
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(maximum+1);}
        var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes.length!=before.size()||after.size()!=before.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey())||!after.isRegularFile()||after.isSymbolicLink()||after.isOther())throw new IOException("Hosted file changed during read");
        return bytes;
    }
    public static String wasmPlatformReason(){
        String os=System.getProperty("os.name",""),arch=System.getProperty("os.arch","");
        return (os.startsWith("Windows")||os.startsWith("Linux"))&&(arch.equals("amd64")||arch.equals("x86_64"))?null:"Bundled hosted WASM runtime requires Windows/Linux x64";
    }
    public static String windowsRuntimeReason(Path runtime,String... files){
        if(!System.getProperty("os.name","").startsWith("Windows")||!System.getProperty("os.arch","").matches("amd64|x86_64"))return "This hosted native core requires Windows x64";
        for(String file:files)if(!Files.isRegularFile(runtime.resolve(file),LinkOption.NOFOLLOW_LINKS))return "Missing hosted runtime: "+file;
        return null;
    }
}
