package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.retro.api.RetroFrame;
import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcJoinNetwork;
import cn.piq.sfchome.server.SfcJoinGate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** Real final-JAR Playback/core/media worker, no Minecraft client/server or audio device. */
public final class SfcWatchPlaybackProbe implements SfcPlayback.Host {
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private volatile SfcPlayback owner;
    private volatile int observed,mediaCalls,audioCalls,closedCalls;
    private volatile boolean ready;
    private volatile short[] lastAudio;
    private volatile RetroFrame copied;
    private int assertions;
    public static void main(String[] args)throws Exception{
        var probe=new SfcWatchPlaybackProbe();Path sfc=Path.of(args[0]).toRealPath(),fc=Path.of(args[1]).toRealPath();
        for(Class<?> c:List.of(SfcPlayback.class,SfcWatchFrames.class,SfcWatchDemand.class,WasmSfcCore.class))probe.check(origin(c).equals(sfc),"SFC final JAR origin "+c);
        for(Class<?> c:List.of(WatchMediaStream.class,RetroFrame.class,CabinetMediaPacket.class))probe.check(origin(c).equals(fc),"FC final JAR origin "+c);
        probe.exercise();
        System.out.println("{\"ok\":true,\"assertions\":"+probe.assertions+",\"production_origin\":\"final-jar-only\",\"production_compiled\":false,\"actual_playback_core_started\":true,\"minecraft_started\":false,\"network_socket_opened\":false,\"audio_device_opened\":false,\"commercial_rom_used\":false,\"host_final_next_frame\":12,\"callback_failure_frame\":6,\"host_frames_run_after_callback_failure\":6,\"p2_media_calls\":0,\"suspend_resume_media_sequence_monotonic\":true}");
    }
    private static Path origin(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private synchronized void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private void await(BooleanSupplier done)throws Exception{
        long until=System.nanoTime()+10_000_000_000L;
        while(!done.getAsBoolean()&&System.nanoTime()<until){for(Runnable task;(task=callbacks.poll())!=null;)task.run();if(owner!=null&&owner.error()!=null)throw new AssertionError(owner.error());Thread.sleep(5);}
        check(done.getAsBoolean(),"worker deadline");
    }
    private void start(int port)throws Exception{
        byte[] rom=SfcTwoPortInputProbe.rom();ready=false;observed=mediaCalls=audioCalls=closedCalls=0;copied=null;
        var session=new SfcHomeNetwork.Session(51,1,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),new UUID(1,1),
                new BlockPos(1,64,0),new UUID(2,2),new UUID(3,3),SfcJoinGate.sha(rom),SfcHomeNetwork.CORE_BUILD,port,new UUID(4,port+1));
        owner=new SfcPlayback(session,rom,new SfcStartupProgress(),this);await(()->ready);
    }
    private void frames(int first,int count)throws Exception{
        for(int sent=0;sent<count;){
            int length=Math.min(4,count-sent);int[] a=new int[length],b=new int[length];
            for(int i=0;i<length;i++)a[i]=((sent+i)&1)==0?0x100:0;
            check(owner.offer(new SfcHomeNetwork.Frames(51,1,first+sent,a,b)),"queue accepted");sent+=length;
        }
        await(()->observed>=first+count);
    }
    private void stop()throws Exception{SfcPlayback old=owner;old.close();owner=null;await(()->!SfcCoreLease.occupied());check(closedCalls==1,"close callback failure isolated");}
    private void exercise()throws Exception{
        try{
            start(0);frames(0,12);
            check(owner.error()==null&&observed==12&&audioCalls==12,"P1 continues real input/audio after observer callback fails");
            check(mediaCalls==6,"media exception opens one-way circuit breaker");
            var still=owner.watchFrame();check(still!=null&&still.pcm48k().length==0,"late join gets silent current picture");
            check(copied!=null&&copied.pcm48k().length>0,"copied raw PCM available");
            mediaSequence(still,copied);
            stop();start(1);frames(0,6);check(mediaCalls==0,"P2 never publishes media");stop();
        }finally{if(owner!=null)stop();}
    }
    private void mediaSequence(RetroFrame silent,RetroFrame sound)throws Exception{
        try(var sender=new WatchMediaStream(new UUID(9,9),new UUID(8,8),true)){
            sender.sending(true);sender.offer(sound);
            List<CabinetMediaPacket> first=collect(sender,true,true);
            long video=first.stream().filter(p->p.kind()==0).mapToLong(CabinetMediaPacket::sequence).max().orElseThrow();
            long audio=first.stream().filter(p->p.kind()==1).mapToLong(CabinetMediaPacket::sequence).max().orElseThrow();
            sender.sending(false);Thread.sleep(120);while(sender.pollOutbound()!=null){};
            sender.sending(true);sender.offer(silent);
            List<CabinetMediaPacket> still=collect(sender,true,false);
            check(still.stream().filter(p->p.kind()==0).allMatch(p->p.sequence()>video),"video sequence retained at zero-to-one viewers");
            check(still.stream().noneMatch(p->p.kind()==1),"pause never repeats audio");
            sender.offer(sound);List<CabinetMediaPacket> resumed=collect(sender,true,true);
            check(resumed.stream().filter(p->p.kind()==1).allMatch(p->p.sequence()>audio),"audio sample clock retained on resume");
        }
    }
    private List<CabinetMediaPacket> collect(WatchMediaStream stream,boolean video,boolean audio)throws Exception{
        var result=new ArrayList<CabinetMediaPacket>();long until=System.nanoTime()+3_000_000_000L;
        while(System.nanoTime()<until){var batch=stream.pollOutbound();if(batch!=null)result.addAll(batch);
            if((!video||result.stream().anyMatch(p->p.kind()==0))&&(!audio||result.stream().anyMatch(p->p.kind()==1)))return result;
            if(stream.error()!=null)throw new AssertionError(stream.error());Thread.sleep(5);}
        throw new AssertionError("media worker deadline");
    }
    @Override public void execute(Runnable task){callbacks.add(task);}
    @Override public boolean isCurrent(SfcPlayback playback){return owner==playback;}
    @Override public void ready(SfcPlayback playback,SfcHomeNetwork.Ready message){ready=true;}
    @Override public void captured(SfcPlayback playback,SfcJoinNetwork.Capture request,byte[] bytes,String sha){}
    @Override public void applied(SfcPlayback playback,SfcJoinNetwork.Capture request,String sha,boolean success){}
    @Override public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int stereoFrames,float gain){lastAudio=Arrays.copyOf(pcm,stereoFrames*2);audioCalls++;}public void close(){}};}
    @Override public void backup(SfcPlayback playback,WasmSfcCore core,int frame){}
    @Override public void observedFrame(int nextFrame,int width,int height,byte[] rgba,short[] pcm,int stereoFrames){observed=nextFrame;}
    @Override public void mediaFrame(SfcPlayback playback,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){
        mediaCalls++;if(mediaCalls==6)throw new IllegalStateException("Expected test-only observer failure");
        copied=SfcWatchFrames.copy(width,height,stride,aspect,rgba,pcm,stereoFrames);
        check(Arrays.equals(lastAudio,copied.pcm48k()),"raw stereo sample frame length exact");check(copied.rotation()==0,"SFC no rotation");
    }
    @Override public void mediaClosed(SfcPlayback playback){closedCalls++;throw new IllegalStateException("Expected test-only close failure");}
}
