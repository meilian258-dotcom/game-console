package cn.piq.fcarcade.cabinet;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Searchable administrator receipt, NOT a catalog or authorization source. Bounded worker IO only. */
final class CabinetContentIndex {
    static final int MAX_INDEXES=1024;
    private CabinetContentIndex() {}
    static Path write(Path sharedDirectory,CabinetGameManifest manifest)throws IOException {
        Path root=sharedDirectory.toAbsolutePath().normalize().resolve("indexes");CabinetGameStore.directory(root);
        String label=manifest.files().getFirst().name();
        // Entry already rejects separators/reserved names. Prefix also protects truncated Windows names.
        // 32 Unicode code points use at most 128 UTF-8 bytes; leave room for the hash on NAME_MAX=255.
        String shortLabel=label.substring(0,label.offsetByCodePoints(0,Math.min(32,label.codePointCount(0,label.length()))));
        String filename="game-"+shortLabel+"--"+manifest.contentId()+".json";
        Path target=root.resolve(filename);
        JsonObject json=new JsonObject();json.addProperty("schema",1);json.addProperty("backend",manifest.backend());
        json.addProperty("game",label);json.addProperty("contentId",manifest.contentId());
        json.addProperty("notice","文件索引，不是游戏授权目录；保留 objects 中的哈希文件名。旧世界源文件不会被删除。缺 BIOS 请把匹配版本放在本地游戏 ZIP 同目录后重新选择并上传，需关机重开本局。");
        JsonArray files=new JsonArray();
        for(int i=0;i<manifest.files().size();i++){
            var entry=manifest.files().get(i);JsonObject file=new JsonObject();
            file.addProperty("role",i==0?"ROM":"BIOS");file.addProperty("name",entry.name());file.addProperty("sha256",entry.sha256());
            file.addProperty("bytes",entry.size());file.addProperty("object","../objects/"+entry.sha256()+".data");files.add(file);
        }
        json.add("files",files);byte[] bytes=(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)+"\n").getBytes(StandardCharsets.UTF_8);
        synchronized(CabinetContentIndex.class){
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){CabinetGameStore.regular(target);if(Files.size(target)!=bytes.length||!Arrays.equals(read(target,bytes.length),bytes))throw new IOException("街机文件索引冲突，请管理员保留并核对："+filename);return target;}
            try(var paths=Files.list(root)){if(paths.limit(MAX_INDEXES).count()>=MAX_INDEXES)throw new IOException("街机文件索引数量已达上限，请管理员检查");}
            Path temporary=root.resolve(".index-"+UUID.randomUUID()+".tmp");
            try{
                try(var out=Files.newByteChannel(temporary,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);}
                Files.move(temporary,target);
            }finally{if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){CabinetGameStore.regular(temporary);Files.delete(temporary);}}
        }
        return target;
    }
    private static byte[] read(Path path,int size)throws IOException {
        try(var in=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
            ByteBuffer data=ByteBuffer.allocate(size);while(data.hasRemaining())if(in.read(data)<0)throw new IOException("Truncated index");
            if(in.read(ByteBuffer.allocate(1))!=-1)throw new IOException("Index changed while reading");return data.array();
        }
    }
}
