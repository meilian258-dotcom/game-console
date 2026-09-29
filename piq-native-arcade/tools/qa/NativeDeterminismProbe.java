package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated QA JVM: actual pinned Engine callbacks, exact one-frame calls, no Minecraft. */
public final class NativeDeterminismProbe {
    public interface States extends NativeCoreWorker.Retro {
        long retro_serialize_size();
        byte retro_serialize(Pointer destination,long size);
        byte retro_unserialize(Pointer source,long size);
    }
    static final int MAX=16*1024*1024;
    static String hash(byte[] data)throws Exception{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static byte[] state(States core)throws Exception{
        long size=core.retro_serialize_size();if(size<1||size>MAX)throw new IOException("Serialize size outside 1..16 MiB: "+size);
        try(Memory data=new Memory(size)){data.clear();if(core.retro_serialize(data,size)==0)throw new IOException("retro_serialize returned false");return data.getByteArray(0,(int)size);}
    }
    static void restore(States core,byte[] data)throws Exception{
        if(data.length<1||data.length>MAX)throw new IOException("State bound");
        try(Memory memory=new Memory(data.length)){memory.write(0,data,0,data.length);if(core.retro_unserialize(memory,data.length)==0)throw new IOException("retro_unserialize returned false");}
    }
    static int[] inputs(int frame){
        int p1=0,p2=0;
        if(frame>=1600&&frame<1620)p1|=1<<2;
        if(frame>=1800&&frame<1820)p1|=1<<3;
        if(frame>=1950){int part=(frame/24)%4;p1|=1<<(4+part);if(frame%11<4)p1|=1;if(frame%17<6)p1|=2;if(frame%37<8)p1|=1<<8;}
        if(frame>=2100){if(frame%13<4)p2|=1;if(frame%29<8)p2|=1<<7;}
        return new int[]{p1,p2,0,0};
    }
    static String frame(States core,NativeCoreWorker.Engine engine,int index)throws Exception{
        engine.current=inputs(index);engine.pcmCount=0;core.retro_run();
        if(engine.failure!=null)throw new IOException("Production callback failed",engine.failure);
        if(engine.pixels==null&&index>8)throw new IOException("No actual frame after bounded startup steps: "+index);
        float dar=engine.aspect>0?((engine.rotation&1)!=0?1f/engine.aspect:engine.aspect):(engine.height==0?0f:(float)engine.width/engine.height);
        if(engine.pixels!=null)BridgeProtocol.frameBounds(engine.width,engine.height,dar,engine.rotation,engine.pcmCount);
        ByteBuffer pixel=ByteBuffer.allocate(engine.pixels==null?0:engine.pixels.length*4).order(ByteOrder.LITTLE_ENDIAN);
        if(engine.pixels!=null)for(int value:engine.pixels)pixel.putInt(value&0xffffff); // Missing initial callback is recorded as zero-sized, never a fabricated frame.
        ByteBuffer sound=ByteBuffer.allocate(engine.pcmCount*2).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<engine.pcmCount;i++)sound.putShort(engine.pcm[i]);
        return index+","+engine.width+","+engine.height+","+Float.floatToIntBits(dar)+","+engine.rotation+","+engine.pcmCount+","+hash(pixel.array())+","+hash(sound.array());
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=7)throw new IllegalArgumentException("dll rom helper output snapshot-frame replay-frames peer-state-or-minus");
        Path dll=Path.of(args[0]).toRealPath(),rom=Path.of(args[1]).toRealPath(),helper=Path.of(args[2]).toRealPath(),out=Path.of(args[3]).toAbsolutePath();
        if(!Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(helper))throw new AssertionError("Wrong production helper origin");
        int split=Integer.parseInt(args[4]),count=Integer.parseInt(args[5]);if(split<1||split>12000||count<1||count>2400)throw new IllegalArgumentException("Frame bound");
        Files.createDirectory(out);
        var core=Native.load(dll.toString(),States.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        var engine=new NativeCoreWorker.Engine(core,rom,new DataOutputStream(OutputStream.nullOutputStream()));
        core.retro_set_environment(engine.environment);core.retro_set_video_refresh(engine.video);
        core.retro_set_audio_sample(engine.audio);core.retro_set_audio_sample_batch(engine.batch);core.retro_set_input_poll(engine.poll);core.retro_set_input_state(engine.input);
        core.retro_init();boolean loaded=false;String stateSha=null,endSha=null,restoredEndSha=null,crossEndSha=null,immediateSaveSha=null,restoredBeforeStepSha=null;long size=0;int localMismatch=0;String firstMismatch="";
        try{
            var game=new NativeCoreWorker.GameInfo();game.path=engine.string(rom.toString());game.write();
            loaded=core.retro_load_game(game)!=0;if(!loaded)throw new IOException("Core rejected ROM");
            var info=new NativeCoreWorker.AvInfo();core.retro_get_system_av_info(info);info.read();engine.timing(info.timing.fps,info.timing.sample_rate);engine.aspect=info.geometry.aspect_ratio;
            List<String> trace=new ArrayList<>();
            for(int i=0;i<split;i++){trace.add(frame(core,engine,i));if(i%600==0)System.err.println("FRAME "+i);}
            byte[] saved=state(core);size=saved.length;stateSha=hash(saved);immediateSaveSha=hash(state(core));
            Path temporary=out.resolve("pending.state");Files.write(temporary,saved,StandardOpenOption.CREATE_NEW);Files.move(temporary,out.resolve("snapshot.bin"),StandardCopyOption.ATOMIC_MOVE);
            List<String> suffix=new ArrayList<>();for(int i=split;i<split+count;i++){String next=frame(core,engine,i);trace.add(next);suffix.add(next);}
            Files.write(out.resolve("baseline.csv"),trace,StandardOpenOption.CREATE_NEW);endSha=hash(state(core));
            restore(core,saved);restoredBeforeStepSha=hash(state(core));List<String> repeated=new ArrayList<>();
            for(int i=split;i<split+count;i++){String next=frame(core,engine,i);repeated.add(next);if(!next.equals(suffix.get(i-split))){localMismatch++;if(firstMismatch.isEmpty())firstMismatch="frame "+i;}}
            Files.write(out.resolve("restored.csv"),repeated,StandardOpenOption.CREATE_NEW);restoredEndSha=hash(state(core));
            if(!args[6].equals("-")){
                Path peer=Path.of(args[6]);long until=System.nanoTime()+30_000_000_000L;while(!Files.isRegularFile(peer)&&System.nanoTime()<until)Thread.sleep(25);
                if(!Files.isRegularFile(peer)||Files.size(peer)>MAX)throw new IOException("Peer state unavailable/bound");
                byte[] other;try(var stream=Files.newInputStream(peer)){other=stream.readNBytes(MAX+1);}restore(core,other);
                List<String> cross=new ArrayList<>();for(int i=split;i<split+count;i++)cross.add(frame(core,engine,i));Files.write(out.resolve("cross.csv"),cross,StandardOpenOption.CREATE_NEW);crossEndSha=hash(state(core));
            }
            var opts=new TreeMap<>(engine.options);Files.writeString(out.resolve("options.txt"),opts.toString(),StandardOpenOption.CREATE_NEW);
            String json="{\"ok\":true,\"actual_core\":true,\"helper_origin_verified\":true,\"snapshot_bytes\":"+size+",\"snapshot_sha256\":\""+stateSha+"\",\"immediate_repeat_save_sha256\":\""+immediateSaveSha+"\",\"restored_before_step_sha256\":\""+restoredBeforeStepSha+"\",\"ending_state_sha256\":\""+endSha+"\",\"restored_ending_state_sha256\":\""+restoredEndSha+"\",\"cross_ending_state_sha256\":\""+crossEndSha+"\",\"local_restore_mismatching_frames\":"+localMismatch+",\"first_local_mismatch\":\""+firstMismatch+"\",\"snapshot_frame\":"+split+",\"replay_frames\":"+count+",\"fps\":"+engine.fps+",\"minecraft_started\":false}";
            Files.writeString(out.resolve("result.json"),json,StandardOpenOption.CREATE_NEW);
        }finally{try{if(loaded)core.retro_unload_game();core.retro_deinit();}finally{Reference.reachabilityFence(engine);}}
        System.err.println("NATIVE_TEARDOWN_COMPLETED");System.err.flush();System.out.flush();
        // The dedicated MAME QA JVM may retain upstream threads after successful native teardown.
        Runtime.getRuntime().halt(0);
    }
}
