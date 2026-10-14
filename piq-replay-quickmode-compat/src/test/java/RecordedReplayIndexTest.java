import cn.piq.replayquick.SableFlagTimeline;
import com.google.gson.JsonParser;
import com.replaymod.replaystudio.studio.ReplayStudio;
import com.replaymod.replaystudio.replay.ZipReplayFile;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Reads an explicit diagnostic recording copy through the compiled production index. */
public final class RecordedReplayIndexTest {
    static byte[] hash(Path file)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=new DigestInputStream(Files.newInputStream(file),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}
        return digest.digest();
    }
    public static SableFlagTimeline loadIndex(Path copy)throws Exception {
        byte[] before=hash(copy);SableFlagTimeline index;
        var registry=PacketTypeRegistry.get(ProtocolVersion.getProtocol(767),State.PLAY);
        try(var file=new ZipReplayFile(new ReplayStudio(),copy.toFile())){index=SableFlagTimeline.read(file,registry);}
        if(!Arrays.equals(before,hash(copy)))throw new AssertionError("Diagnostic copy changed during indexing");
        return index;
    }
    public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    static void run(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Expected diagnostic recording copy and private trace");
        var index=loadIndex(Path.of(args[0]));int checks=1,players=0,known=0;
        var events=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject().getAsJsonArray("quickEvents");
        for(var element:events){var event=element.getAsJsonObject();if(!event.get("type").getAsString().equals("EntityTeleport"))continue;
            int id=event.getAsJsonObject("parsed").get("id").getAsInt(),time=event.get("seekTarget").getAsInt();
            if(!index.isPlayerAt(id,time))throw new AssertionError("Recorded player not classified");players++;checks++;
            if(index.flagAt(id,time)!=SableFlagTimeline.Flag.FALSE)throw new AssertionError("Recorded false not found");known++;checks++;
        }
        if(players!=8||known!=8)throw new AssertionError("Wrong known sample count");checks++;
        System.out.println("RecordedReplayIndexTest checks="+checks+" players="+players+" knownFalse="+known+" entities="+index.entityCount()+" changes="+index.changeCount()+" PASS (actual production reader, unchanged diagnostic copy; no game)");
    }
}
