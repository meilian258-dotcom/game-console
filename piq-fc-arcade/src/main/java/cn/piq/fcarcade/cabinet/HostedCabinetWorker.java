package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.server.hosted.*;
import cn.piq.retro.api.RetroFrame;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceLocation;

/** Owns staging and encoding away from the server tick. No player/world/network callbacks here. */
final class HostedCabinetWorker implements AutoCloseable {
    private final UUID room, stream;
    private final ResourceLocation backend;
    private final ServerCoreContext context;
    private final CabinetGameManifest manifest;
    private final CabinetGameStore store;
    private final Path staging;
    private final HostedMediaQueue<List<CabinetMediaPacket>> outbound=new HostedMediaQueue<>(8,b->b.getFirst().kind()==0);
    private final HostedInputQueue inputs=new HostedInputQueue();
    private volatile boolean closed,terminated,ready;
    private volatile String error;
    private volatile ServerCoreHandle core;
    private final Thread worker;
    private final HostedServerLimits.Lease lease;
    private final Set<UUID> devices;
    HostedCabinetWorker(UUID room,UUID stream,ResourceLocation backend,ServerCoreContext context,CabinetGameManifest manifest,Path objects,Path staging){
        this(room,stream,backend,context,manifest,objects,staging,null);
    }
    HostedCabinetWorker(UUID room,UUID stream,ResourceLocation backend,ServerCoreContext context,CabinetGameManifest manifest,Path objects,Path staging,HostedServerLimits.Lease lease){
        this(room,stream,backend,context,manifest,objects,staging,lease,Set.of(context.roomId()));
    }
    HostedCabinetWorker(UUID room,UUID stream,ResourceLocation backend,ServerCoreContext context,CabinetGameManifest manifest,Path objects,Path staging,HostedServerLimits.Lease lease,Set<UUID> devices){
        this(room,stream,backend,context,manifest,new CabinetGameStore(objects),staging,lease,devices);
    }
    HostedCabinetWorker(UUID room,UUID stream,ResourceLocation backend,ServerCoreContext context,CabinetGameManifest manifest,CabinetGameStore store,Path staging,HostedServerLimits.Lease lease,Set<UUID> devices){
        this.room=room;this.stream=stream;this.backend=backend;this.context=context;this.manifest=manifest;this.store=store;this.staging=staging;
        this.lease=lease;this.devices=Set.copyOf(devices);
        worker=Thread.ofPlatform().daemon(true).name("Retro-Hosted-Cabinet-"+room).start(this::run);
    }
    void inputs(int[] value){if(!closed&&!inputs.offer(value)){error="服务端输入队列超限，请重新加入";close();}}
    boolean supportsCoinPreservingRelease(){var active=core;return active!=null&&active.supportsCoinPreservingRelease();}
    boolean coin(int port){synchronized(inputs){return ready()&&error==null&&core!=null&&core.isReady()&&core.error()==null&&core.supportsCoinPreservingRelease()&&inputs.coin(port);}}
    void releaseGameplayPort(int port,boolean paid){synchronized(inputs){
        var active=core;
        if(paid&&active!=null&&active.supportsCoinPreservingRelease()){
            inputs.releasePreservingCoins(port);active.releaseGameplayPortKeepingCoin(port);
        }else{inputs.release(port);if(active!=null)active.releasePort(port);}
    }}
    void releasePort(int port){synchronized(inputs){inputs.release(port);var active=core;if(active!=null)active.releasePort(port);}}
    boolean ready(){return ready&&!closed;}
    boolean terminated(){return terminated;}
    boolean uses(UUID device){return !terminated&&devices.contains(device);}
    String error(){return error;}
    boolean budget(net.minecraft.server.MinecraftServer server,int bytes){return HostedServerLimits.reserveMedia(server,lease,bytes);}
    List<CabinetMediaPacket> poll(){return outbound.poll();}
    private void run(){
        var created=new ArrayList<Path>();
        boolean stagingCreated=false;
        try{
            CabinetGameStore.directory(staging.getParent());
            Files.createDirectory(staging); // Unique room directory; never reuse or overwrite an old run.
            stagingCreated=true;
            for(var entry:manifest.files()){
                if(closed)return;
                Path source=store.verifiedPath(entry),destination=staging.resolve(entry.name());
                try(var in=Files.newByteChannel(source,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                    var out=Files.newByteChannel(destination,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){
                    created.add(destination);ByteBuffer buffer=ByteBuffer.allocate(32768);long total=0;
                    while(!closed){int n=in.read(buffer);if(n<0)break;if(n==0)continue;total+=n;if(total>entry.size())throw new java.io.IOException("Shared game changed");buffer.flip();while(buffer.hasRemaining())out.write(buffer);buffer.clear();}
                }
                if(closed)return;CabinetGameStore.verify(destination,entry);
            }
            if(closed)return;
            core=ServerCoreRegistry.open(backend,context,staging.resolve(manifest.files().getFirst().name()));
            long sequence=0,samples=0,started=System.nanoTime();var videoPacer=new HostedVideoPacer();
            while(!closed){
                if(core.error()!=null)throw new IllegalStateException(core.error());
                if(!core.isReady()&&System.nanoTime()-started>60_000_000_000L)throw new IllegalStateException("服务端核心启动超时");
                ready=core.isReady();
                // Serialize forced release with forwarding; never revive a stale queued press.
                synchronized(inputs){for(int[] masks;(masks=inputs.poll())!=null;)core.offerInputs(masks[0],masks[1],masks[2],masks[3]);}
                var frame=core.pollFrame();long now=System.nanoTime();
                if(frame!=null){
                    short[] pcm=frame.pcm48k();
                    for(int offset=0;offset<pcm.length;){
                        int length=Math.min(4800,pcm.length-offset);byte[] audio=CabinetMediaCodec.encodePcm(pcm,offset,length);
                        outbound.offer(List.of(new CabinetMediaPacket(room,stream,samples,1,0,1,0,0,1F,0,audio.length,audio)));
                        samples+=length/2;offset+=length;
                    }
                    if(outbound.videoRoom()&&videoPacer.take(now,CabinetHostingConfig.videoFps())){
                        var image=CabinetMediaCodec.encodeVideo(new RetroFrame(frame.width(),frame.height(),frame.abgr(),frame.displayAspect(),frame.rotation(),new short[0]));
                        byte[] data=image.data();int count=(data.length+24575)/24576;var batch=new ArrayList<CabinetMediaPacket>(count);
                        for(int i=0;i<count;i++)batch.add(new CabinetMediaPacket(room,stream,sequence,0,i,count,image.width(),image.height(),image.displayAspect(),image.rotation(),image.width()*image.height()*2,Arrays.copyOfRange(data,i*24576,Math.min(data.length,(i+1)*24576))));
                        sequence++;outbound.offer(List.copyOf(batch));
                    }
                }
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }catch(InterruptedException interrupted){Thread.interrupted();}
        catch(Exception|LinkageError failure){if(!closed)error="服务端托管失败："+failure.getClass().getSimpleName()+" · "+String.valueOf(failure.getMessage());}
        finally{
            closed=true;ready=false;
            if(core!=null){signalCoreClose();while(!core.isTerminated())try{TimeUnit.MILLISECONDS.sleep(50);}catch(InterruptedException ignored){Thread.interrupted();}}
            if(core!=null&&core.error()!=null){
                error="服务端核心收尾失败："+core.error();
                cn.piq.fcarcade.FcArcadeMod.LOGGER.error("Hosted cabinet {} ({}) finalization failed: {}",room,backend,core.error());
            }
            // Delete only files created by this run, after the child/core really stopped. Unknown files remain.
            for(Path file:created)try{CabinetGameStore.regular(file);Files.delete(file);}catch(Exception ignored){}
            if(stagingCreated)try{CabinetGameStore.requireDirectory(staging);Files.delete(staging);}catch(Exception ignored){}
            outbound.clear();terminated=true;
            if(lease!=null)lease.close();
        }
    }
    private void signalCoreClose(){try{core.close();}catch(RuntimeException|LinkageError ignored){/* Retain admission until actual termination. */}}
    @Override public void close(){closed=true;inputs.clear();if(core!=null)signalCoreClose();worker.interrupt();}
}
