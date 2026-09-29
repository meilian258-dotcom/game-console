package cn.piq.nativearcade.bridge;

import com.sun.jna.*;
import java.io.*;
import java.lang.ref.Reference;
import java.nio.file.*;
import java.util.*;

/** Actual pinned MAME DLL initialization, production environment callback, original firmware only. */
public final class NativeCoreOptions26Probe {
    private static int checks;
    private static void require(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[]args)throws Exception{
        System.err.println("QA_PHASE main");
        Path dll=Path.of(args[0]).toRealPath(),rom=Path.of(args[1]).toRealPath();
        require(Path.of(NativeCoreWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[2]).toRealPath()),"Engine comes from supplied helper JAR");
        System.err.println("QA_PHASE dll_load");
        var core=Native.load(dll.toString(),NativeCoreWorker.Retro.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
        System.err.println("QA_PHASE dll_loaded");
        require(core.retro_api_version()==1,"Actual libretro ABI");
        var engine=new NativeCoreWorker.Engine(core,rom,new DataOutputStream(new ByteArrayOutputStream()));
        var observed=new ArrayList<String>();
        NativeCoreWorker.Environment callback=(command,data)->{
            String name=command==15?data.getPointer(0).getString(0,"UTF-8"):null;
            byte result=engine.environment.invoke(command,data);
            if("mame_buttons_profiles".equals(name))observed.add(result==1?data.getPointer(Native.POINTER_SIZE).getString(0,"UTF-8"):"missing");
            return result;
        };
        core.retro_set_environment(callback);core.retro_set_video_refresh(engine.video);
        core.retro_set_audio_sample(engine.audio);core.retro_set_audio_sample_batch(engine.batch);
        core.retro_set_input_poll(engine.poll);core.retro_set_input_state(engine.input);
        System.err.println("QA_PHASE init");core.retro_init();System.err.println("QA_PHASE initialized");boolean loaded=false;
        try{
            var game=new NativeCoreWorker.GameInfo();game.path=engine.string(rom.toString());game.write();
            System.err.println("QA_PHASE load_game");loaded=core.retro_load_game(game)!=0;System.err.println("QA_PHASE game_loaded="+loaded);require(loaded,"Actual original firmware loaded");
            require(!observed.isEmpty(),"Actual MAME queried the exact buttons_profiles variable");
            require(observed.stream().allMatch("disabled"::equals),"Every actual core GET receives disabled");
            require("disabled".equals(engine.options.get("mame_buttons_profiles")),"Pinned core declared disabled by default (not enabled)");
            // Explicit policy wins even if a future core declares enabled first.
            engine.options.put("mame_buttons_profiles","enabled");
            try(var variable=new Memory(Native.POINTER_SIZE*2L)){
                variable.setPointer(0,engine.string("mame_buttons_profiles"));variable.setPointer(Native.POINTER_SIZE,null);
                require(engine.environment.invoke(15,variable)==1,"Policy variable recognized");
                require("disabled".equals(variable.getPointer(Native.POINTER_SIZE).getString(0,"UTF-8")),"Policy overrides enabled default");
            }
            boolean sound=false;
            for(int frame=0;frame<120;frame++){
                if(frame%30==0)System.err.println("QA_PHASE frame="+frame);
                engine.buttons.offer(frame<60?1:0,0,0,0);engine.nextInputFrame();core.retro_run();
                require(engine.failure==null,"Production callback succeeds frame "+frame);
                require(engine.pcmCount<=BridgeProtocol.MAX_PCM,"Bounded actual PCM");
                for(int i=0;i<engine.pcmCount;i++)sound|=engine.pcm[i]!=0;
                engine.pcmCount=0;
            }
            require(engine.width==260&&engine.height==224,"Actual original driver framebuffer");
            require(engine.pixels!=null&&engine.pixels.length==260*224,"Actual pixels complete");
            require(sound,"Actual native sound samples observed");
        }finally{
            try{System.err.println("QA_PHASE unload");if(loaded)core.retro_unload_game();System.err.println("QA_PHASE deinit");core.retro_deinit();System.err.println("QA_PHASE finished");}
            finally{Reference.reachabilityFence(callback);Reference.reachabilityFence(engine);}
        }
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_mame_core_started\":true,\"actual_core_option_queries\":"+observed.size()+",\"game_profiles_forced_disabled\":true,\"pinned_default_was_already_disabled\":true,\"original_frames\":120,\"commercial_rom_loaded\":false,\"minecraft_started\":false,\"natural_jvm_exit_tested\":false,\"qa_halt_after_all_assertions_and_native_teardown\":true}");
        System.out.flush();System.err.flush();
        if(System.out.checkError()||System.err.checkError())throw new IOException("QA result flush failed");
        // This is only the direct-DLL QA JVM, not the production parent bridge.
        // Never reached on an assertion, callback, native teardown or output failure.
        Runtime.getRuntime().halt(0);
    }
}
