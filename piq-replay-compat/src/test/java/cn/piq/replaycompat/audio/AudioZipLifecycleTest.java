package cn.piq.replaycompat.audio;

import cn.piq.replaycompat.api.AudioReplay;
import com.replaymod.replaystudio.replay.*;
import com.replaymod.replaystudio.studio.ReplayStudio;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static cn.piq.replaycompat.audio.AudioTestSupport.*;

/** Actual pinned ReplayStudio ZIP save/reopen, not merely a memory-archive contract. */
public final class AudioZipLifecycleTest {
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("piq-replay-audio-zip-").toRealPath();
        try {
            Path path=directory.resolve("captured.mcpr");Object owner=new Object(),source=new Object();
            try(ReplayFile file=new ZipReplayFile(new ReplayStudio(),null,path.toFile())){
                try(var out=file.write("unrelated.bin")){out.write(new byte[]{3,4,5});}
                AudioBridge.start(owner,file,()->25L);
                check(AudioReplay.recording(),"public API recording presence");
                Object token=AudioReplay.captureToken(source);
                AudioReplay.capture(token,source,pcm(480,2,2345),0,1920,48000,2);
                AudioReplay.reset(source);
                AudioReplay.capture(token,source,pcm(480,2,9999),0,1920,48000,2);
                AudioBridge.stop(owner);check(!AudioReplay.recording(),"stop immediately ends public capture");
                AudioBridge.finish(owner);AudioBridge.finish(owner);file.save();
            }
            try(ZipFile zip=new ZipFile(path.toFile())){
                check(zip.getEntry(AudioTrackFiles.INDEX)!=null && zip.getEntry(AudioTrackFiles.entry(0))!=null,"real mcpr contains custom audio index and PCM chunk");
                check(Arrays.equals(zip.getInputStream(zip.getEntry("unrelated.bin")).readAllBytes(),new byte[]{3,4,5}),"existing recorder entry preserved");
            }
            try(ReplayFile file=new ZipReplayFile(new ReplayStudio(),path.toFile())){
                var index=AudioTrackFiles.readIndex(file);var frames=AudioTrackFiles.readChunk(file,index.refs().get(0));
                check(frames.size()==1 && frames.get(0).startMicros()==25000,"realzip excludes flushedtoken write");
                check(frames.get(0).sample(0,0)==2345,"actualzip exact finalPCM survives save close reopen");
                Sink sink=new Sink();AudioPlayback playback=new AudioPlayback(file,sink);playback.tick(25,false,1,false);
                await(()->sink.offers()>0,"actualzip asynchronously replays PCM");check(sample(sink.first(),0)==2345,"actualzip sink sample");playback.close();
            }
            Path empty=directory.resolve("empty.mcpr");
            try(ReplayFile file=new ZipReplayFile(new ReplayStudio(),null,empty.toFile())){
                Object second=new Object();AudioBridge.start(second,file,()->0L);AudioBridge.finish(owner);
                check(AudioReplay.recording(),"oldowner finalization cannot stop later recording");
                AudioBridge.finish(second);file.save();
            }
            try(ReplayFile file=new ZipReplayFile(new ReplayStudio(),empty.toFile())){
                check(AudioTrackFiles.readIndex(file).refs().isEmpty(),"emptycapture represented without invented audio");
            }
            System.out.println("AudioZipLifecycleTest: "+checks+" checks passed");
        }finally{
            AudioBridge.close();
            try(var paths=Files.walk(directory)){
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){
                    if(!path.toAbsolutePath().normalize().startsWith(directory))throw new IllegalStateException("Testcleanup escaped directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
