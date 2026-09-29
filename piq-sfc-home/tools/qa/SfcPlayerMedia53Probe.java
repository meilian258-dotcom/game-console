package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.retro.api.RetroFrame;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfchome.net.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;

/** Actual production decode worker and wire codecs; no Minecraft, ROM, GPU or audio device. */
public final class SfcPlayerMedia53Probe {
    private static final AtomicInteger assertions=new AtomicInteger();
    private static void assertTrue(boolean value){assertTrue(value,"expected true");}
    private static void assertTrue(boolean value,String message){assertions.incrementAndGet();if(!value)throw new AssertionError(message);}
    private static void assertFalse(boolean value){assertTrue(!value);}
    private static void assertFalse(boolean value,String message){assertTrue(!value,message);}
    private static void assertNull(Object value){assertTrue(value==null,"expected null: "+value);}
    private static void assertEquals(Object expected,Object value){assertTrue(Objects.equals(expected,value),"expected "+expected+", actual "+value);}
    private static void assertThrows(Class<? extends Throwable> expected,Runnable action){try{action.run();}catch(Throwable failure){assertTrue(expected.isInstance(failure));return;}throw new AssertionError("missing "+expected);}
    private static Path origin(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    public static void main(String[] args)throws Exception{
        Path sfc=Path.of(args[0]).toRealPath(),fc=Path.of(args[1]).toRealPath();
        for(Class<?> c:List.of(SfcPlayback.class,SfcHomeNetwork.class,SfcHostedNetwork.class,SfcPlaybackMode.class))assertEquals(sfc,origin(c));
        for(Class<?> c:List.of(CabinetMediaCodec.class,CabinetMediaPacket.class,RetroFrame.class))assertEquals(fc,origin(c));
        var probe=new SfcPlayerMedia53Probe();probe.sessionCodecsPreserveAllModeAndRoleDecisions();
        probe.playerMediaReceiverDecodesWithoutRomOrCoreAndKeepsForeignLease();
        probe.receiverRejectsStaleSessionEpochRecipientAndProducerBeforeQueueing();
        assertTrue(Set.of(sfc,Path.of(args[2]).toRealPath()).contains(origin(WasmSfcCore.class)));
        probe.playerHostStopsAndBacksUpOnMediaFailure(true);probe.playerHostStopsAndBacksUpOnMediaFailure(false);
        System.out.println("{\"ok\":true,\"assertions\":"+assertions.get()+",\"production_origin\":\"jar-only\",\"production_compiled\":false,\"cases\":5,\"actual_receiver\":true,\"actual_codec\":true,\"remote_loaded_rom\":false,\"remote_started_core\":false,\"host_started_core\":true,\"host_media_failure_backup\":true,\"commercial_rom_used\":false,\"minecraft_started\":false,\"network_socket_opened\":false,\"audio_device_opened\":false}");
    }
    private static final UUID SOURCE=new UUID(53,1),STREAM=new UUID(53,2),RECEIPT=new UUID(53,3);
    private static final String SHA="53".repeat(32);
    private static SfcHomeNetwork.Session session(int mode,boolean host){
        return session(mode,host,SHA);
    }
    private static SfcHomeNetwork.Session session(int mode,boolean host,String sha){
        return new SfcHomeNetwork.Session(53,2,ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),new UUID(1,1),
                new BlockPos(1,64,0),new UUID(2,2),new UUID(3,3),sha,SfcHomeNetwork.CORE_BUILD,host?-1:1,RECEIPT,host,mode,SOURCE,STREAM);
    }
    private static <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(buf,value);T decoded=codec.decode(buf);assertEquals(0,buf.readableBytes());return decoded;}
        finally{buf.release();}
    }
    private static SfcHostedNetwork.Stream packet(CabinetMediaPacket media,UUID receipt){
        return new SfcHostedNetwork.Stream(53,2,receipt,new CabinetRoomNetwork.Media(media.room(),media.hostMember(),media.sequence(),media.kind(),media.index(),media.count(),media.width(),media.height(),media.aspect(),media.rotation(),media.rawLength(),media.data()));
    }
    private static CabinetMediaPacket video(long sequence){
        var encoded=CabinetMediaCodec.encodeVideo(new RetroFrame(2,1,new int[]{0xff0000ff,0xff00ff00},2,0,new short[0]));
        return new CabinetMediaPacket(SOURCE,STREAM,sequence,0,0,1,2,1,2,0,4,encoded.data());
    }
    private static CabinetMediaPacket pcm(long sequence){
        return new CabinetMediaPacket(SOURCE,STREAM,sequence,1,0,1,0,0,1,0,8,CabinetMediaCodec.encodePcm(new short[]{100,-100,200,-200},0,4));
    }
    private static final class Harness implements SfcPlayback.Host {
        volatile SfcPlayback receiver;volatile boolean ready;
        volatile boolean failPublication;volatile int observed;
        final AtomicInteger backups=new AtomicInteger();
        final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
        final AtomicInteger audio=new AtomicInteger(),closed=new AtomicInteger(),unsafe=new AtomicInteger();
        public void execute(Runnable action){callbacks.add(action);}
        public boolean isCurrent(SfcPlayback p){return p==receiver;}
        public void ready(SfcPlayback p,SfcHomeNetwork.Ready value){ready=p==receiver;}
        public void captured(SfcPlayback p,SfcJoinNetwork.Capture r,byte[] b,String hash){unsafe.incrementAndGet();}
        public void applied(SfcPlayback p,SfcJoinNetwork.Capture r,String hash,boolean success){unsafe.incrementAndGet();}
        public void backup(SfcPlayback p,WasmSfcCore core,int frame){if(p.session.executionHost()){assertTrue(core.saveState().length>0);backups.incrementAndGet();}else unsafe.incrementAndGet();}
        public void observedFrame(int frame,int width,int height,byte[] rgba,short[] pcm,int stereoFrames){observed=frame;}
        public void mediaFrame(SfcPlayback p,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){if(failPublication)throw new IllegalStateException("Expected QA media failure");}
        public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){
            public void submit(short[] pcm,int frames,float gain){assertTrue(frames*2<=pcm.length);audio.incrementAndGet();}
            public void close(){closed.incrementAndGet();}
        };}
        void drain(){for(Runnable action;(action=callbacks.poll())!=null;)action.run();}
        void await(BooleanSupplier condition)throws Exception{
            long deadline=System.nanoTime()+15_000_000_000L;
            while(!condition.getAsBoolean()&&System.nanoTime()<deadline){drain();if(receiver!=null)assertNull(receiver.error());Thread.sleep(5);}
            drain();assertTrue(condition.getAsBoolean(),"production receiver did not complete within bounded wait");
        }
    }
    private static void closeAndJoin(SfcPlayback worker)throws Exception{
        worker.close();var f=SfcPlayback.class.getDeclaredField("thread");f.setAccessible(true);Thread thread=(Thread)f.get(worker);
        thread.join(3000);assertFalse(thread.isAlive(),"media worker terminated");
    }
    void sessionCodecsPreserveAllModeAndRoleDecisions(){
        for(int mode=0;mode<3;mode++)for(boolean host:new boolean[]{false,true}){
            var decoded=round(SfcHomeNetwork.Session.CODEC,session(mode,host));
            assertEquals(mode,decoded.syncMode());assertEquals(host,decoded.executionHost());
            assertEquals(SfcPlaybackMode.receivesMedia(mode,host),decoded.receivesMedia());
            assertEquals(mode==0,decoded.playerHosted());assertEquals(mode==1,decoded.checksState());
            assertEquals(SOURCE,decoded.mediaSource());assertEquals(STREAM,decoded.mediaStream());
        }
        assertThrows(IllegalArgumentException.class,()->session(3,false));
    }
    void playerMediaReceiverDecodesWithoutRomOrCoreAndKeepsForeignLease()throws Exception{
        Harness host=new Harness();
        try(SfcCoreLease otherCore=SfcCoreLease.acquire()){
            SfcPlayback worker=new SfcPlayback(round(SfcHomeNetwork.Session.CODEC,session(0,false)),new byte[0],new SfcStartupProgress(),host);host.receiver=worker;
            try{
                host.await(()->host.ready);assertTrue(SfcCoreLease.occupied());
                // Force requests which would be unsafe for a media-only controller: they must be ignored.
                worker.capture(new SfcJoinNetwork.Capture(53,2,new UUID(4,4),0));
                worker.restore(new SfcJoinNetwork.Capture(53,2,new UUID(4,4),0),new byte[]{1},SHA);
                worker.offerMedia(round(SfcHostedNetwork.Stream.CODEC,packet(video(1),RECEIPT)));
                worker.offerMedia(round(SfcHostedNetwork.Stream.CODEC,packet(pcm(1),RECEIPT)));
                host.await(()->worker.started()&&host.audio.get()>0);
                assertNull(worker.error());assertEquals(0,host.unsafe.get());assertTrue(SfcCoreLease.occupied());
                worker.resetMedia();host.await(()->host.closed.get()>0);
                worker.offerMedia(round(SfcHostedNetwork.Stream.CODEC,packet(video(2),RECEIPT)));
                worker.offerMedia(round(SfcHostedNetwork.Stream.CODEC,packet(pcm(2),RECEIPT)));
                host.await(()->host.audio.get()>1);
            }finally{closeAndJoin(worker);}
            assertTrue(SfcCoreLease.occupied(),"media close must not release a different core");
            assertEquals(0,host.unsafe.get());
        }
        assertFalse(SfcCoreLease.occupied());
    }
    void receiverRejectsStaleSessionEpochRecipientAndProducerBeforeQueueing()throws Exception{
        Harness host=new Harness();SfcPlayback worker=new SfcPlayback(session(0,false),new byte[0],new SfcStartupProgress(),host);host.receiver=worker;
        try{
            host.await(()->host.ready);
            var valid=packet(video(1),RECEIPT);
            worker.offerMedia(packet(video(1),new UUID(9,9)));
            worker.offerMedia(new SfcHostedNetwork.Stream(52,2,RECEIPT,valid.media()));
            worker.offerMedia(new SfcHostedNetwork.Stream(53,1,RECEIPT,valid.media()));
            for(boolean wrongSource:new boolean[]{false,true}){
                var m=video(1);var foreign=new CabinetMediaPacket(wrongSource?new UUID(9,9):SOURCE,wrongSource?STREAM:new UUID(9,9),m.sequence(),m.kind(),m.index(),m.count(),m.width(),m.height(),m.aspect(),m.rotation(),m.rawLength(),m.data());
                worker.offerMedia(packet(foreign,RECEIPT));
            }
            var q=SfcPlayback.class.getDeclaredField("mediaInput");q.setAccessible(true);
            assertTrue(((Queue<?>)q.get(worker)).isEmpty());assertFalse(worker.started());assertEquals(0,host.audio.get());
            worker.offerMedia(valid);host.await(worker::started);assertEquals(0,host.unsafe.get());
        }finally{closeAndJoin(worker);}
    }
    void playerHostStopsAndBacksUpOnMediaFailure(boolean callback)throws Exception{
        byte[] rom=SfcTwoPortInputProbe.rom();Harness host=new Harness();host.failPublication=callback;
        var worker=new SfcPlayback(session(0,true,SfcClientFiles.hash(rom)),rom,new SfcStartupProgress(),host);host.receiver=worker;
        try{
            host.await(()->host.ready);assertTrue(SfcCoreLease.occupied());
            assertTrue(worker.offer(new SfcHomeNetwork.Frames(53,2,0,new int[]{0},new int[]{0})));
            if(!callback){host.await(()->host.observed==1);worker.playerMediaFailed();}
            long deadline=System.nanoTime()+10_000_000_000L;
            while((worker.error()==null||SfcCoreLease.occupied())&&System.nanoTime()<deadline){host.drain();Thread.sleep(5);}
            assertTrue(worker.error()!=null&&worker.error().contains("主持音画发布失败"));assertFalse(SfcCoreLease.occupied());
            assertEquals(1,host.observed);assertEquals(1,host.backups.get());assertEquals(0,host.unsafe.get());
        }finally{closeAndJoin(worker);}
    }
}
