package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.CabinetMediaSender;
import cn.piq.fcarcade.cabinet.WatchDescriptor;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.retro.api.RetroFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import java.util.concurrent.atomic.AtomicReference;

/** Execution host's media tap. Optional for local sync; essential for player-hosted media. */
final class SfcWatchPublisher {
    private static final AtomicReference<Publication> CURRENT=new AtomicReference<>();
    private static final SfcWatchDemand ORDER=new SfcWatchDemand();
    private SfcWatchPublisher() {}

    static void demand(WatchNetwork.HostDemand message){
        var client=Minecraft.getInstance().getConnection();
        Connection connection=client==null?null:client.getConnection();
        ORDER.connection(connection);
        SfcPlayback owner=SfcHomeClient.currentPlayback();
        boolean authorized=authorized(owner,message.descriptor(),connection);
        if(!authorized){
            // Shared connection cleanup may dispatch an old high-revision stop while MC already
            // exposes the new connection. Never let that descriptor advance the new history.
            Publication old=CURRENT.get();
            if(old!=null&&old.descriptor.equals(message.descriptor())&&(!message.needed()||old.connection!=connection))close();
            return;
        }
        long previous=ORDER.revision();
        if(!ORDER.acceptAuthorized(connection,message.revision(),message.descriptor(),message.needed(),authorized)){
            if(message.revision()>previous||ORDER.blocked()||!message.needed()&&ORDER.matches(message.revision(),message.descriptor()))suspend();
            return;
        }
        // The server owns endpoint liveness. A same-dimension host may walk beyond
        // its client chunk view while another player keeps the powered TV loaded.
        Publication current=CURRENT.get();
        if(current==null||current.owner!=owner||current.connection!=connection||!current.descriptor.equals(message.descriptor())){
            close();
            try{
                current=new Publication(owner,message.descriptor(),connection);
                CURRENT.set(current);
            }catch(RuntimeException|LinkageError failure){close();ORDER.block();owner.playerMediaFailed();return;}
        }
        if(current.failed){ORDER.block();current.owner.playerMediaFailed();return;}
        if(!current.needed){
            current.needed=true;current.stream.sending(true);
            try{RetroFrame picture=owner.watchFrame();if(picture!=null){current.last.set(picture);current.stream.offer(picture);}}
            catch(RuntimeException|LinkageError failure){failed(current);return;}
        }
        current.refreshed=System.nanoTime();
    }

    private static boolean authorized(SfcPlayback owner,WatchDescriptor descriptor,Connection connection){
        var client=Minecraft.getInstance().getConnection();
        return owner!=null&&owner.session.executionHost()&&SfcHomeClient.isCurrent(owner)&&client!=null
                &&client.getConnection()==connection&&connection.isConnected()
                &&owner.session.controllerLease().equals(descriptor.hostLease())
                &&SfcWatchClient.matches(descriptor,owner.session);
    }

    /** Called synchronously by the real core worker: copy only, no Minecraft/network/codec calls. */
    static void frame(SfcPlayback owner,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){
        Publication current=CURRENT.get();
        if(current==null||current.owner!=owner||!current.needed||current.failed)return;
        try{
            RetroFrame copy=SfcWatchFrames.copy(width,height,stride,aspect,rgba,pcm,stereoFrames);
            current.last.set(SfcWatchFrames.silent(copy));
            current.lastFrame=System.nanoTime();
            current.stream.offer(copy);
        }catch(RuntimeException|LinkageError failure){current.failed=true;owner.playerMediaFailed();}
    }

    static void tick(){
        var client=Minecraft.getInstance().getConnection();
        Connection connection=client==null?null:client.getConnection();
        ORDER.connection(connection);
        Publication current=CURRENT.get();if(current==null)return;
        long now=System.nanoTime();
        if(!authorized(current.owner,current.descriptor,current.connection)){
            close();ORDER.block();return;
        }
        if(current.failed){failed(current);return;}
        if(now-current.refreshed>5_000_000_000L){suspend();return;}
        if(!current.needed)return;
        try{
            if(current.stream.error()!=null){failed(current);return;}
            if(now-current.lastFrame>=1_000_000_000L){
                RetroFrame still=current.last.get();
                if(still!=null){current.stream.offer(still);current.lastFrame=now;}
            }
            // The shared actual-Connection window bounds all game and watch sends together.
            for(int i=0;i<8;i++){
                var batch=current.stream.pollOutbound();if(batch==null)break;
                boolean admitted=CabinetMediaSender.watchServerbound(current.connection,batch);
                current.stream.transportResult(batch,admitted);
                if(!admitted)break;
            }
        }catch(RuntimeException|LinkageError failure){failed(current);}
    }
    private static void failed(Publication current){current.failed=true;suspend();ORDER.block();current.owner.playerMediaFailed();}
    static void closed(SfcPlayback owner){Publication current=CURRENT.get();if(current!=null&&current.owner==owner){close();ORDER.block();}}
    // Keep sequence/audio clocks throughout the source lifetime, including zero viewers.
    private static void suspend(){Publication current=CURRENT.get();if(current!=null){current.needed=false;current.stream.sending(false);current.last.set(null);}}
    private static void close(){Publication current=CURRENT.getAndSet(null);if(current!=null)current.stream.close();}
    private static final class Publication {
        final SfcPlayback owner;final WatchDescriptor descriptor;final Connection connection;final WatchMediaStream stream;
        final AtomicReference<RetroFrame> last=new AtomicReference<>();
        volatile long refreshed=System.nanoTime(),lastFrame=System.nanoTime();volatile boolean failed,needed;
        Publication(SfcPlayback owner,WatchDescriptor descriptor,Connection connection){
            this.owner=owner;this.descriptor=descriptor;this.connection=connection;
            stream=new WatchMediaStream(descriptor.source(),descriptor.hostLease(),true);
        }
    }
}
