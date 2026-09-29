package cn.piq.fcarcade.server;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.fcarcade.session.LockstepState;
import cn.piq.retro.api.RetroFrame;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One FC headless executor plus bounded encoder. No ServerPlayer or mutable world access. */
final class FcHomeHostedRun implements AutoCloseable {
    final UUID source=UUID.randomUUID();volatile UUID token=UUID.randomUUID();
    private final Object publication=new Object();private volatile boolean resetting;
    private final AtomicReference<byte[]> snapshot=new AtomicReference<>();
    private final HostedMediaQueue<List<CabinetMediaPacket>> media=new HostedMediaQueue<>(8,b->b.getFirst().kind()==0);
    private volatile ServerCoreHandle core;private volatile boolean closed,terminated;private volatile String error;
    private final Thread worker;
    private final HostedServerLimits.Lease admission;
    FcHomeHostedRun(ServerCoreContext context,Path rom,String romSha,byte[] initial,HostedServerLimits.Lease admission){
        this.admission=Objects.requireNonNull(admission);
        var managed=new NesManagedState(romSha,initial,state->{synchronized(publication){if(!resetting)snapshot.set(state);}},()->{synchronized(publication){snapshot.set(null);media.clear();resetting=false;}});
        var scoped=new ServerCoreContext(context.gameRoot(),context.saveRoot(),context.ownerId(),context.roomId(),context.nesVariant(),managed);
        worker=Thread.ofPlatform().daemon(true).name("PIQ FC home hosted encoder").start(()->run(scoped,rom));
    }
    boolean ready(){var h=core;return !closed&&!resetting&&h!=null&&h.isReady();}
    boolean terminated(){return terminated;}
    String error(){return error;}
    boolean budget(net.minecraft.server.MinecraftServer server,int bytes){return HostedServerLimits.reserveMedia(server,admission,bytes);}
    byte[] snapshot(){return snapshot.getAndSet(null);}
    List<CabinetMediaPacket> poll(){return media.poll();}
    static int retroMask(int nes){return (nes&0xfc)|((nes&1)<<8)|((nes&2)>>>1);}
    void input(LockstepState.FrameStep step){var h=core;if(h!=null&&!closed)h.offerFrameInput(retroMask(step.playerOneMask()),retroMask(step.playerTwoMask()),0,0,step.zapperState());}
    void release(int port){var h=core;if(h!=null&&!closed)h.releasePort(port);}
    void reset(){synchronized(publication){var h=core;if(h==null||closed)return;resetting=true;token=UUID.randomUUID();snapshot.set(null);media.clear();h.reset();}}
    private void publish(UUID generation,List<CabinetMediaPacket> batch){synchronized(publication){if(!closed&&!resetting&&token.equals(generation))media.offer(batch);}}
    private void run(ServerCoreContext context,Path rom){
        try{
            if(closed)return;core=ServerCoreRegistry.open(CabinetBackends.NES,context,rom);long sequence=0,audioFrame=0,began=System.nanoTime();boolean firstReady=false;var videoPacer=new HostedVideoPacer();
            while(!closed){
                firstReady|=core.isReady();if(!firstReady&&System.nanoTime()-began>60_000_000_000L)throw new IllegalStateException("FC 服务端核心启动超时");
                if(core.error()!=null)throw new IllegalStateException(core.error());UUID generation=token;var frame=resetting?null:core.pollFrame();long now=System.nanoTime();
                if(frame!=null){
                    for(int at=0;at<frame.pcm48k().length;){int n=Math.min(4800,frame.pcm48k().length-at);byte[] data=CabinetMediaCodec.encodePcm(frame.pcm48k(),at,n);publish(generation,List.of(new CabinetMediaPacket(source,generation,audioFrame,1,0,1,0,0,1,0,data.length,data)));audioFrame+=n/2;at+=n;}
                    if(media.videoRoom()&&videoPacer.take(now,CabinetHostingConfig.videoFps())){var encoded=CabinetMediaCodec.encodeVideo(new RetroFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),frame.rotation(),new short[0]));byte[] data=encoded.data();int count=(data.length+24575)/24576;var parts=new ArrayList<CabinetMediaPacket>();
                        for(int i=0;i<count;i++)parts.add(new CabinetMediaPacket(source,generation,sequence,0,i,count,encoded.width(),encoded.height(),encoded.displayAspect(),encoded.rotation(),encoded.width()*encoded.height()*2,Arrays.copyOfRange(data,i*24576,Math.min(data.length,(i+1)*24576))));sequence++;publish(generation,List.copyOf(parts));}
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }catch(InterruptedException ignored){Thread.interrupted();}catch(Throwable failure){if(!closed)error="FC server core: "+failure.getMessage();}
        finally{closed=true;var h=core;if(h!=null){signalClose(h);while(!h.isTerminated())try{TimeUnit.MILLISECONDS.sleep(50);}catch(InterruptedException ignored){Thread.interrupted();}if(h.error()!=null)error=h.error();}if(error!=null)cn.piq.fcarcade.FcArcadeMod.LOGGER.error("FC hosted {} finalization: {}",source,error);media.clear();terminated=true;admission.close();}
    }
    private void signalClose(ServerCoreHandle h){try{h.close();}catch(RuntimeException|LinkageError failure){error="FC server close: "+failure.getMessage();}}
    @Override public void close(){closed=true;var h=core;if(h!=null)signalClose(h);worker.interrupt();}
}
