package cn.piq.fcarcade.server;

import cn.piq.fcarcade.server.hosted.ServerCoreFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

/** Real play activity, separate from save-file rename/read timestamps. Missing history gets a grace period. */
final class PersonalSaveActivity {
    private final Path root;
    private final java.util.Map<UUID,Long> recent=new java.util.HashMap<>();
    PersonalSaveActivity(Path saves){root=saves.toAbsolutePath().normalize().resolve("personal-play-activity");}
    long lastPlayed(UUID player,Instant now)throws IOException{
        ServerCoreFiles.directory(root,true);Path file=root.resolve(player+".bin");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){played(player,now);return now.toEpochMilli();}
        long time=ByteBuffer.wrap(ServerCoreFiles.read(file,8,8)).getLong();
        if(time<=0||time>now.toEpochMilli())return now.toEpochMilli();
        return Math.max(time,recent.getOrDefault(player,0L));
    }
    void played(UUID player,Instant now)throws IOException{
        recent.merge(player,now.toEpochMilli(),Math::max); // Keep this process safe even if the disk write fails.
        ServerCoreFiles.directory(root,true);Path file=root.resolve(player+".bin");
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe activity file");
        Path tmp=Files.createTempFile(root,".activity-",".tmp");
        try{Files.write(tmp,ByteBuffer.allocate(8).putLong(now.toEpochMilli()).array(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
}
