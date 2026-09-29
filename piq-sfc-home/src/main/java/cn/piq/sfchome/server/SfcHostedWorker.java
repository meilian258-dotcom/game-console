package cn.piq.sfchome.server;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.retro.api.RetroFrame;
import cn.piq.sfchome.SfcHomeMod;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Server-only ROM staging and media encoding; never calls a world/player/network from this worker. */
final class SfcHostedWorker implements AutoCloseable {
    private final UUID source,stream;
    private final ServerCoreContext context;
    private final Path library;
    private final String rom;
    private final AutoCloseable capacity;
    private final HostedMediaQueue<List<CabinetMediaPacket>> outbound=new HostedMediaQueue<>(8,b->b.getFirst().kind()==0);
    private volatile boolean closed,ready,terminated;
    private volatile String error;
    private volatile ServerCoreHandle core;
    private final Thread worker;
    SfcHostedWorker(UUID source,UUID stream,ServerCoreContext context,Path library,String rom,AutoCloseable capacity){
        this.source=source;this.stream=stream;this.context=context;this.library=library;this.rom=rom;this.capacity=Objects.requireNonNull(capacity);
        worker=Thread.ofPlatform().daemon(true).name("SFC-Home-Hosted-"+source).start(this::run);
    }
    // Ready-only server ingress delegates to the handle's bounded independent port FIFOs.
    // There is deliberately no latest-state slot in this encoding worker to erase quick edges.
    void inputs(int p1,int p2){var current=core;if(!closed&&current!=null&&current.isReady())current.offerInput(p1,p2);}
    void releasePort(int port){var current=core;if(!closed&&current!=null)current.releasePort(port);}
    boolean ready(){return ready&&!closed;}
    boolean terminated(){return terminated;}
    String error(){return error;}
    List<CabinetMediaPacket> poll(){return outbound.poll();}
    boolean reserveMedia(net.minecraft.server.MinecraftServer server,int bytes){return capacity instanceof HostedServerLimits.Lease lease&&HostedServerLimits.reserveMedia(server,lease,bytes);}
    boolean reset(){var current=core;if(closed||current==null||!current.isReady()||!current.supportsReset())return false;current.clearInput();current.reset();outbound.clear();return true;}
    private void run(){
        Path staging=null,file=null;
        try{
            byte[] bytes=new SfcRomStore(library).read(rom); // Rehash normalized bytes before using the captured cartridge.
            if(closed)return;
            // read() has checked every library ancestor; create a unique child, never overwrite a library ROM.
            staging=Files.createTempDirectory(library,".hosted-");file=staging.resolve("game.sfc");
            Files.write(file,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);bytes=null;
            if(closed)return;
            core=ServerCoreRegistry.open(SfcHomeMod.CABINET_BACKEND,context,file);
            long sequence=0,samples=0,began=System.nanoTime();var videoPacer=new HostedVideoPacer();
            while(!closed){
                if(core.error()!=null)throw new IllegalStateException(core.error());
                if(!core.isReady()&&System.nanoTime()-began>60_000_000_000L)throw new IllegalStateException("服务端核心启动超时");
                ready=core.isReady();
                var frame=core.pollFrame();long now=System.nanoTime();
                if(frame!=null){
                    short[] pcm=frame.pcm48k();
                    for(int offset=0;offset<pcm.length;){int count=Math.min(4800,pcm.length-offset);byte[] audio=CabinetMediaCodec.encodePcm(pcm,offset,count);
                        outbound.offer(List.of(new CabinetMediaPacket(source,stream,samples,1,0,1,0,0,1F,0,audio.length,audio)));samples+=count/2;offset+=count;}
                    if(outbound.videoRoom()&&videoPacer.take(now,CabinetHostingConfig.videoFps())){
                        var image=CabinetMediaCodec.encodeVideo(new RetroFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),frame.rotation(),new short[0]));
                        byte[] data=image.data();int count=(data.length+24575)/24576;var batch=new ArrayList<CabinetMediaPacket>(count);
                        for(int i=0;i<count;i++)batch.add(new CabinetMediaPacket(source,stream,sequence,0,i,count,image.width(),image.height(),image.displayAspect(),image.rotation(),image.width()*image.height()*2,Arrays.copyOfRange(data,i*24576,Math.min(data.length,(i+1)*24576))));
                        sequence++;outbound.offer(List.copyOf(batch));
                    }
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }catch(InterruptedException interrupted){Thread.interrupted();}
        catch(Exception|LinkageError failure){if(!closed){error="SFC 服务端托管失败："+failure.getClass().getSimpleName();org.slf4j.LoggerFactory.getLogger("PIQ SFC Home Hosted").warn("Hosted home worker failed",failure);}}
        finally{
            ready=false;closed=true;
            if(core!=null){core.close();while(!core.isTerminated())try{TimeUnit.MILLISECONDS.sleep(50);}catch(InterruptedException ignored){Thread.interrupted();}if(error==null&&core.error()!=null){error="SFC 服务端托管收尾失败，请检查服务器日志/备份。";org.slf4j.LoggerFactory.getLogger("PIQ SFC Home Hosted").warn("Hosted core stopped with failure: {}",core.error());}}
            // Only the two exact objects created by this run; no recursive deletion or unknown-file cleanup.
            if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}
            if(staging!=null)try{Files.delete(staging);}catch(Exception ignored){}
            outbound.clear();terminated=true;try{capacity.close();}catch(Exception failure){org.slf4j.LoggerFactory.getLogger("PIQ SFC Home Hosted").warn("Hosted capacity release failed",failure);}
        }
    }
    @Override public void close(){closed=true;if(core!=null)core.close();worker.interrupt();}
}
