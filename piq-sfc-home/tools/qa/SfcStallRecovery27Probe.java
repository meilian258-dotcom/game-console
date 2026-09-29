package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.*;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.net.*;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** Actual WASM + production worker, with only game-thread frame delivery stalled. */
public final class SfcStallRecovery27Probe implements SfcPlayback.Host {
    private final boolean failAudioReset;
    private SfcStallRecovery27Probe(boolean failAudioReset){this.failAudioReset=failAudioReset;}
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final List<String> expectedVideo=new ArrayList<>(),expectedAudio=new ArrayList<>();
    private volatile SfcPlayback playback;
    private volatile int observed,audio,discarded,media;
    private volatile String workerFailure,finalState;
    private boolean ready;
    private double fps;
    private int assertions;
    private String expectedState;
    private void check(boolean ok,String reason){assertions++;if(!ok)throw new AssertionError(reason);}
    private static Path origin(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private static int mask(int frame,int port){return frame%7==port?1<<((frame/7+port)%12):0;}
    private static String pcmHash(short[] pcm,int frames){byte[] bytes=new byte[frames*4];for(int i=0;i<frames*2;i++){bytes[i*2]=(byte)pcm[i];bytes[i*2+1]=(byte)(pcm[i]>>>8);}return SfcExecutionCore.digest(bytes);}
    private void pump(){for(Runnable task;(task=callbacks.poll())!=null;)task.run();if(workerFailure!=null)throw new AssertionError(workerFailure);if(playback!=null&&playback.error()!=null)throw new AssertionError(playback.error());}
    private void await(BooleanSupplier done)throws Exception{long until=System.nanoTime()+15_000_000_000L;while(!done.getAsBoolean()&&System.nanoTime()<until){pump();Thread.sleep(2);}pump();check(done.getAsBoolean(),"worker wait deadline");}
    private void reference(byte[] rom,int count){
        try(var core=new SfcExecutionCore()){
            core.loadRom(SfcRomImage.fromBytes(rom));fps=core.initialize();byte[] rgba=new byte[0];short[] pcm=new short[4096];
            for(int frame=0;frame<count;frame++){
                var result=core.runFrame(new SfcControllerState(mask(frame,0)),new SfcControllerState(mask(frame,1)));
                if(rgba.length!=result.videoMode().requiredRgbaBytes())rgba=new byte[result.videoMode().requiredRgbaBytes()];
                if(pcm.length<result.requiredPcmShorts())pcm=new short[result.requiredPcmShorts()];
                core.copyRgbaFrame(rgba);int length=core.copyAudioPcm16(pcm);
                expectedVideo.add(SfcExecutionCore.digest(rgba));expectedAudio.add(pcmHash(pcm,length));
            }
            expectedState=SfcExecutionCore.digest(core.saveState());
        }
    }
    private SfcHomeNetwork.Frames frames(int first){int[] a=new int[3],b=new int[3];for(int i=0;i<3;i++){a[i]=mask(first+i,0);b[i]=mask(first+i,1);}return new SfcHomeNetwork.Frames(2727,1,first,a,b);}
    private Map<String,Object> exercise(int stallSeconds)throws Exception{
        int stalledFrames=stallSeconds*60,firstDelayed=60,resume=firstDelayed+stalledFrames,count=resume+120;
        byte[] rom=SfcTwoPortInputProbe.rom();reference(rom,count);
        var session=new SfcHomeNetwork.Session(2727,1,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),new UUID(1,1),new BlockPos(2,64,0),new UUID(2,2),new UUID(3,3),SfcExecutionCore.digest(rom),SfcHomeNetwork.CORE_BUILD,-1,UUID.randomUUID(),true);
        long catchupNanos=-1;int maxLag=0,head=0;
        try{
            playback=new SfcPlayback(session,rom,new SfcStartupProgress(),this);await(()->ready);
            var pending=new ArrayList<SfcHomeNetwork.Frames>();long start=System.nanoTime(),resumedAt=-1;
            long interval=Math.round(3_000_000_000.0/fps);
            for(int first=0;first<count;first+=3){
                long target=start+(long)(first/3)*interval;
                while(System.nanoTime()<target){if(first<firstDelayed||first>=resume)pump();Thread.sleep(1);}
                head=first+3;
                if(first>=firstDelayed&&first<resume){pending.add(frames(first));continue;}
                if(!pending.isEmpty()){
                    for(var batch:pending)check(playback.offer(batch),"delayed authoritative batch accepted");pending.clear();resumedAt=System.nanoTime();
                }
                check(playback.offer(frames(first)),"live authoritative batch accepted");pump();
                maxLag=Math.max(maxLag,head-observed);
                if(resumedAt>=0&&catchupNanos<0&&head-observed<=9)catchupNanos=System.nanoTime()-resumedAt;
            }
            await(()->observed==count);
            check(catchupNanos>=0&&catchupNanos<2_000_000_000L,"normal backlog converged within two seconds of resumed delivery");
            check(discarded==1,"old audio flushed once for one stall, not every emulation frame");
            check(audio<count-stalledFrames/2,"catch-up audio was not replayed");
            check(media<count-stalledFrames/2,"old spectator media was not replayed");
            playback.capture(new SfcJoinNetwork.Capture(2727,1,UUID.randomUUID(),count));await(()->finalState!=null);
            check(expectedState.equals(finalState),"all press/release frames preserved exact final WASM state");
            if(failAudioReset)check(audio<=firstDelayed,"audio device reset failure mutes sound without stopping gameplay");
            var result=new LinkedHashMap<String,Object>();result.put("stall_seconds",stallSeconds);result.put("audio_reset_failure_injected",failAudioReset);result.put("compared_rgba_and_stereo_pcm_frames",observed);result.put("max_observed_lag_frames",maxLag);result.put("catchup_ms",catchupNanos/1_000_000);result.put("audio_flushes",discarded);result.put("audio_frames_submitted",audio);result.put("spectator_frames_submitted",media);result.put("exact_final_state_sha",finalState);result.put("assertions",assertions);return result;
        }finally{
            if(playback!=null)playback.close();long until=System.nanoTime()+10_000_000_000L;
            while(SfcCoreLease.occupied()&&System.nanoTime()<until)Thread.sleep(5);
            check(!SfcCoreLease.occupied(),"real worker releases core lease after completion/cancel");
        }
    }
    public static void main(String[] args)throws Exception{
        Path production=Path.of(args[0]).toRealPath(),core=Path.of(args[1]).toRealPath();
        if(!origin(SfcPlayback.class).equals(production)||!origin(SfcPlaybackPacing.class).equals(production)||!origin(WasmSfcCore.class).equals(core))throw new AssertionError("unexpected production/core origin");
        var results=new ArrayList<Map<String,Object>>();for(int seconds:new int[]{1,3})results.add(new SfcStallRecovery27Probe(false).exercise(seconds));results.add(new SfcStallRecovery27Probe(true).exercise(1));
        System.out.println("PIQ_STALL_QA "+new Gson().toJson(Map.of("ok",true,"actual_wasm",true,"minecraft_started",false,"sound_device_opened",false,"original_diagnostic_rom_only",true,"cases",results)));
    }
    public void execute(Runnable task){callbacks.add(task);}public boolean isCurrent(SfcPlayback p){return p==playback;}
    public void ready(SfcPlayback p,SfcHomeNetwork.Ready value){ready=true;check(Math.abs(fps-value.targetFps())<.000001,"reference and worker use same actual FPS");}
    public void captured(SfcPlayback p,SfcJoinNetwork.Capture r,byte[] bytes,String sha){finalState=sha;}
    public void applied(SfcPlayback p,SfcJoinNetwork.Capture r,String sha,boolean success){throw new AssertionError("unexpected join restore");}
    public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int frames,float gain){audio++;}public void discardQueued(){discarded++;if(failAudioReset)throw new IllegalStateException("injected sound-device close failure");}public void close(){}};}
    public void backup(SfcPlayback p,WasmSfcCore core,int frame){}
    public void mediaFrame(SfcPlayback p,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int frames){media++;}
    public void observedFrame(int frame,int width,int height,byte[] rgba,short[] pcm,int frames){
        if(frame!=observed+1)workerFailure="frame was dropped/reordered: "+observed+" -> "+frame;
        else if(!expectedVideo.get(frame-1).equals(SfcExecutionCore.digest(rgba)))workerFailure="video differs at actual frame "+frame;
        else if(!expectedAudio.get(frame-1).equals(pcmHash(pcm,frames)))workerFailure="audio differs at actual frame "+frame;
        observed=frame;
    }
}
