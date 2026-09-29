package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.file.*;
import java.util.*;

/** New experiment only: two warm and one cold core load the exact same raw snapshot. */
public final class NativeReloadBarrierProbe {
    private static final List<String> timing=new ArrayList<>();
    private static void save(Path out,String name,byte[] bytes)throws Exception {
        Path part=out.resolve(name+".pending");Files.write(part,bytes,StandardOpenOption.CREATE_NEW);
        Files.move(part,out.resolve(name),StandardCopyOption.ATOMIC_MOVE);
    }
    private static byte[] readState(Path p)throws Exception {
        long until=System.nanoTime()+60_000_000_000L;
        while(!Files.isRegularFile(p)&&System.nanoTime()<until)Thread.sleep(20);
        if(!Files.isRegularFile(p)||Files.size(p)<1||Files.size(p)>NativeDeterminismProbe.MAX)throw new IOException("Peer snapshot missing/bound");
        try(var in=Files.newInputStream(p)){byte[] data=in.readNBytes(NativeDeterminismProbe.MAX+1);if(data.length>NativeDeterminismProbe.MAX)throw new IOException("State grew");return data;}
    }
    private static void trace(NativeDeterminismProbe.States core,NativeCoreWorker.Engine engine,Path out,String name,int first,int count,boolean neutral)throws Exception {
        long begin=System.nanoTime();var rows=new ArrayList<String>(count);
        for(int i=first;i<first+count;i++)rows.add(NativeDeterminismProbe.frame(core,engine,neutral?0:i));
        long nanos=System.nanoTime()-begin;Files.write(out.resolve(name+".csv"),rows,StandardOpenOption.CREATE_NEW);
        timing.add("\""+name+"\":{\"steps\":"+count+",\"nanoseconds\":"+nanos+",\"steps_per_second_including_full_hashes\":"+(count*1e9/nanos)+"}");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=8)throw new IllegalArgumentException("dll rom helper out split count peerSnapshot-or-minus warm|cold|neutral");
        Path dll=Path.of(args[0]).toRealPath(),rom=Path.of(args[1]).toRealPath(),helper=Path.of(args[2]).toRealPath(),out=Path.of(args[3]);
        int split=Integer.parseInt(args[4]),count=Integer.parseInt(args[5]);String mode=args[7];boolean cold=mode.equals("cold"),neutral=mode.equals("neutral");
        if(!Set.of("warm","cold","neutral").contains(mode))throw new IllegalArgumentException("Mode");
        if(split<16||split>12000||count<1||count>2400)throw new IllegalArgumentException("Step bounds");
        if(!Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(helper))throw new AssertionError("Production helper origin");
        Files.createDirectory(out);var core=Native.load(dll.toString(),NativeDeterminismProbe.States.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        var engine=new NativeCoreWorker.Engine(core,rom,new DataOutputStream(OutputStream.nullOutputStream()));
        // Observe the production callback's actual answers, not its SET_VARIABLES defaults.
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
            trace(core,engine,out,"initial",0,cold?8:neutral?split+count:split,neutral);save(out,"own-before.bin",NativeDeterminismProbe.state(core));
            byte[] shared=args[6].equals("-")?readState(out.resolve("own-before.bin")):readState(Path.of(args[6]));
            if(!cold&&!neutral){trace(core,engine,out,"baseline",split,count,false);save(out,"baseline-end.bin",NativeDeterminismProbe.state(core));}
            for(int round=1;round<=2;round++){
                NativeDeterminismProbe.restore(core,shared);save(out,"barrier"+round+"-before.bin",NativeDeterminismProbe.state(core));
                trace(core,engine,out,"barrier"+round,split,count,false);save(out,"barrier"+round+"-end.bin",NativeDeterminismProbe.state(core));
            }
            Files.writeString(out.resolve("options.txt"),effective.toString(),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("declared-options.txt"),new TreeMap<>(engine.options).toString(),StandardOpenOption.CREATE_NEW);
            Files.writeString(out.resolve("result.json"),"{\"ok\":true,\"helper_origin_verified\":true,\"mode\":\""+mode+"\",\"effective_environment_queries\":"+effective.size()+",\"fps\":"+engine.fps+",\"loaded_snapshot_sha256\":\""+NativeDeterminismProbe.hash(shared)+"\",\"timing\":{"+String.join(",",timing)+"}}",StandardOpenOption.CREATE_NEW);
        }finally{try{if(loaded)core.retro_unload_game();core.retro_deinit();}finally{Reference.reachabilityFence(engine);Reference.reachabilityFence(auditEnvironment);}}
        System.err.println("NATIVE_TEARDOWN_COMPLETED");System.err.flush();System.out.flush();Runtime.getRuntime().halt(0);
    }
}
