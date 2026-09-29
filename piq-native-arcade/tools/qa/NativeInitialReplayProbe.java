package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.file.*;
import java.util.*;

/** Read-only experiment, production core/helper. Each owned JVM starts fresh, canonicalizes at frame 32, and replays. */
public final class NativeInitialReplayProbe {
    private static void save(Path out,String name,byte[] bytes)throws Exception {
        Files.write(out.resolve(name),bytes,StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=7)throw new IllegalArgumentException("dll rom helper out initial-or-minus history checkpoint");
        Path dll=Path.of(args[0]).toRealPath(),rom=Path.of(args[1]).toRealPath(),helper=Path.of(args[2]).toRealPath(),out=Path.of(args[3]);
        int count=Integer.parseInt(args[5]),interval=Integer.parseInt(args[6]);
        if(count<600||count>36000||interval<60||interval>1200||count%interval!=0)throw new IllegalArgumentException("Bounded replay/checkpoints");
        if(!Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(helper))throw new AssertionError("Production helper origin");
        Files.createDirectory(out);var core=Native.load(dll.toString(),NativeDeterminismProbe.States.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        var engine=new NativeCoreWorker.Engine(core,rom,new DataOutputStream(OutputStream.nullOutputStream()));
        Map<String,String> effective=new TreeMap<>();
        NativeCoreWorker.Environment auditEnvironment=(command,data)->{
            byte accepted=engine.environment(command,data);
            if(command==15&&accepted!=0&&data!=null){Pointer key=data.getPointer(0),value=data.getPointer(Native.POINTER_SIZE);if(key!=null&&value!=null)effective.put(key.getString(0,"UTF-8"),value.getString(0,"UTF-8"));}
            return accepted;
        };
        core.retro_set_environment(auditEnvironment);core.retro_set_video_refresh(engine.video);core.retro_set_audio_sample(engine.audio);core.retro_set_audio_sample_batch(engine.batch);core.retro_set_input_poll(engine.poll);core.retro_set_input_state(engine.input);
        core.retro_init();boolean loaded=false;
        try{
            var game=new NativeCoreWorker.GameInfo();game.path=engine.string(rom.toString());game.write();loaded=core.retro_load_game(game)!=0;if(!loaded)throw new IOException("ROM rejected");
            var info=new NativeCoreWorker.AvInfo();core.retro_get_system_av_info(info);info.read();engine.timing(info.timing.fps,info.timing.sample_rate);engine.aspect=info.geometry.aspect_ratio;
            for(int i=0;i<32;i++)NativeDeterminismProbe.frame(core,engine,i);
            byte[] initial;
            if(args[4].equals("-")){initial=NativeDeterminismProbe.state(core);save(out,"initial.bin",initial);}
            else {Path p=Path.of(args[4]);try(var in=Files.newInputStream(p)){initial=in.readNBytes(NativeDeterminismProbe.MAX+1);}if(initial.length<1||initial.length>NativeDeterminismProbe.MAX)throw new IOException("State bounds");}
            NativeDeterminismProbe.restore(core,initial);save(out,"checkpoint-0.bin",NativeDeterminismProbe.state(core));
            long started=System.nanoTime(),block=started;var timings=new ArrayList<String>();
            try(var trace=Files.newBufferedWriter(out.resolve("frames.csv"),StandardOpenOption.CREATE_NEW)){
                for(int i=0;i<count;i++){
                    trace.write(NativeDeterminismProbe.frame(core,engine,32+i));trace.newLine();
                    if((i+1)%interval==0){save(out,"checkpoint-"+(i+1)+".bin",NativeDeterminismProbe.state(core));long now=System.nanoTime();timings.add("{\"through_frame\":"+(i+1)+",\"steps\":"+interval+",\"nanoseconds\":"+(now-block)+"}");block=now;}
                }
            }
            long nanos=System.nanoTime()-started;
            Files.writeString(out.resolve("options.txt"),effective.toString(),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("result.json"),"{\"ok\":true,\"helper_origin_verified\":true,\"canonical_bootstrap_steps\":32,\"history_frames\":"+count+",\"history_packed_bytes\":"+(count*8L)+",\"initial_snapshot_bytes\":"+initial.length+",\"initial_snapshot_sha256\":\""+NativeDeterminismProbe.hash(initial)+"\",\"fps\":"+engine.fps+",\"replay_nanoseconds_including_full_hashes_checkpoints_io\":"+nanos+",\"steps_per_second\":"+(count*1e9/nanos)+",\"blocks\":["+String.join(",",timings)+"]}",StandardOpenOption.CREATE_NEW);
        }finally{try{if(loaded)core.retro_unload_game();core.retro_deinit();}finally{Reference.reachabilityFence(engine);Reference.reachabilityFence(auditEnvironment);}}
        System.err.println("NATIVE_TEARDOWN_COMPLETED");System.err.flush();System.out.flush();Runtime.getRuntime().halt(0);
    }
}
