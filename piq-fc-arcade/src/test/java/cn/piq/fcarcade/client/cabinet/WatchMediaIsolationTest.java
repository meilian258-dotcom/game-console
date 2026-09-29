package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.retro.api.RetroFrame;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent media-only lifecycle tests. Uses synthetic pixels/PCM, never an emulator or audio device. */
class WatchMediaIsolationTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static RetroFrame frame(short[] pcm){return new RetroFrame(2,2,new int[]{-1,0xff0000ff,0xff00ff00,0xffff0000},4F/3F,1,pcm);}
    private static CabinetMediaPacket video(UUID source,UUID host,long sequence){var e=CabinetMediaCodec.encodeVideo(frame(new short[0]));return new CabinetMediaPacket(source,host,sequence,0,0,1,e.width(),e.height(),e.displayAspect(),e.rotation(),8,e.data());}
    private static CabinetMediaPacket audio(UUID source,UUID host,long sequence,short[] pcm){var b=CabinetMediaCodec.encodePcm(pcm,0,pcm.length);return new CabinetMediaPacket(source,host,sequence,1,0,1,0,0,1,0,b.length,b);}
    private static <T> T await(Supplier<T> poll)throws Exception{long end=System.nanoTime()+2_000_000_000L;T result;while((result=poll.get())==null&&System.nanoTime()<end)Thread.sleep(5);assertNotNull(result,"bounded worker completion");return result;}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Thread worker(WatchMediaStream facade)throws Exception{return (Thread)field(field(facade,"stream"),"worker");}
    @Test void wrongSourceAndHostCannotPolluteCurrentVideoOrPcm()throws Exception{
        try(var viewer=new WatchMediaStream(id(1),id(2),false)){
            viewer.accept(video(id(99),id(2),500)); viewer.accept(video(id(1),id(99),500));
            viewer.accept(audio(id(99),id(2),500,new short[]{99,-99}));viewer.accept(audio(id(1),id(99),500,new short[]{98,-98}));
            viewer.accept(video(id(1),id(2),0)); viewer.accept(audio(id(1),id(2),0,new short[]{3,-3}));
            assertArrayEquals(frame(new short[0]).abgr(),await(viewer::pollVideo).abgr());
            assertArrayEquals(new short[]{3,-3},await(viewer::pollAudio)); assertNull(viewer.pollVideo());assertNull(viewer.pollAudio());assertNull(viewer.error());
        }
    }
    @Test void replacementHostAndNewReceiverMayRestartSequenceButOldHostCannot()throws Exception{
        var first=new WatchMediaStream(id(1),id(2),false);
        first.accept(video(id(1),id(2),99));assertNotNull(await(first::pollVideo));first.close();
        try(var next=new WatchMediaStream(id(1),id(3),false)){
            next.accept(video(id(1),id(2),1000)); next.accept(audio(id(1),id(2),1000,new short[]{9,-9}));
            next.accept(video(id(1),id(3),0));next.accept(audio(id(1),id(3),0,new short[]{1,-1}));
            assertNotNull(await(next::pollVideo));assertArrayEquals(new short[]{1,-1},await(next::pollAudio));assertNull(first.pollVideo());
        }finally{first.close();}
    }
    @Test void staticVideoRefreshNeverReplaysOldPcm()throws Exception{
        try(var sender=new WatchMediaStream(id(1),id(2),true);var viewer=new WatchMediaStream(id(1),id(2),false)){
            sender.sending(true);sender.offer(frame(new short[]{10,-10,20,-20}));
            var first=new ArrayList<CabinetMediaPacket>();long end=System.nanoTime()+2_000_000_000L;
            while(first.size()<2&&System.nanoTime()<end){var batch=sender.pollOutbound();if(batch!=null){first.addAll(batch);batch.forEach(viewer::accept);}Thread.sleep(5);}
            assertNotNull(await(viewer::pollVideo));assertArrayEquals(new short[]{10,-10,20,-20},await(viewer::pollAudio));
            sender.offer(frame(new short[0]));var refresh=await(sender::pollOutbound);
            assertFalse(refresh.isEmpty());assertTrue(refresh.stream().allMatch(p->p.kind()==0));
            refresh.forEach(viewer::accept);assertNotNull(await(viewer::pollVideo));assertNull(viewer.pollAudio());
        }
    }
    @Test void roleSeparationHasNoReceiveOnHostOrPublishOnViewer()throws Exception{
        try(var host=new WatchMediaStream(id(1),id(2),true);var viewer=new WatchMediaStream(id(1),id(2),false)){
            host.accept(video(id(1),id(2),0));host.accept(audio(id(1),id(2),0,new short[]{1,-1}));
            viewer.sending(true);viewer.offer(frame(new short[]{1,-1}));Thread.sleep(30);
            assertNull(host.pollVideo());assertNull(host.pollAudio());assertNull(viewer.pollOutbound());
        }
    }
    @Test void closeTerminatesDaemonAndRejectsAllLaterOffersAndReceives()throws Exception{
        var sender=new WatchMediaStream(id(1),id(2),true);var viewer=new WatchMediaStream(id(1),id(2),false);
        try{assertTrue(worker(sender).isDaemon());assertTrue(worker(viewer).isDaemon());sender.close();viewer.close();
            worker(sender).join(1000);worker(viewer).join(1000);assertFalse(worker(sender).isAlive());assertFalse(worker(viewer).isAlive());
            sender.sending(true);sender.offer(frame(new short[]{1,-1}));viewer.accept(video(id(1),id(2),0));
            assertNull(sender.pollOutbound());assertNull(viewer.pollVideo());assertNull(viewer.pollAudio());assertNull(viewer.error());
        }finally{sender.close();viewer.close();}
    }
    @SuppressWarnings("unchecked")
    @Test void facadeRejectsSimulatedLateWorkerResultsAfterCloseDeterministically()throws Exception{
        var viewer=new WatchMediaStream(id(1),id(2),false);viewer.close();worker(viewer).join(1000);assertFalse(worker(viewer).isAlive());
        Object underlying=field(viewer,"stream");
        // Model a worker that completed a previously captured job after close. This is a
        // deterministic race-result fixture, not a claim that an actual scheduler race occurred.
        ((AtomicReference<RetroFrame>)field(underlying,"received")).set(frame(new short[0]));
        ((BlockingQueue<short[]>)field(underlying,"audio")).offer(new short[]{7,-7});
        ((BlockingQueue<List<CabinetMediaPacket>>)field(underlying,"outbound")).offer(List.of(video(id(1),id(2),0)));
        assertNull(viewer.pollVideo());assertNull(viewer.pollAudio());assertNull(viewer.pollOutbound());assertNull(viewer.error());viewer.close();
    }
    @Test void audioOnlyTrafficDoesNotMaskMissingVideoTimeout()throws Exception{
        try(var viewer=new WatchMediaStream(id(1),id(2),false)){
            viewer.accept(video(id(1),id(2),0));assertNotNull(await(viewer::pollVideo));
            Object underlying=field(viewer,"stream");Field lastVideo=underlying.getClass().getDeclaredField("lastVideo");lastVideo.setAccessible(true);
            lastVideo.setLong(underlying,System.nanoTime()-16_000_000_000L);
            viewer.accept(audio(id(1),id(2),0,new short[]{1,-1}));assertArrayEquals(new short[]{1,-1},await(viewer::pollAudio));assertNotNull(viewer.error());
        }
    }
    @Test void ordinaryReceiverStillTimesOutBeforeFirstVideoAfterFifteenSeconds()throws Exception{
        try(var viewer=new WatchMediaStream(id(1),id(2),false)){
            Object underlying=field(viewer,"stream");Field began=underlying.getClass().getDeclaredField("began");began.setAccessible(true);began.setLong(underlying,System.nanoTime()-16_000_000_000L);
            viewer.accept(audio(id(1),id(2),0,new short[]{1,-1}));assertNotNull(await(viewer::pollAudio));assertNotNull(viewer.error());
        }
    }
    @Test void hostedStartupGraceNeverExtendsEstablishedVideoTimeout()throws Exception{
        try(var viewer=new CabinetMediaStream(id(1),id(2),false,true)){
            Field began=viewer.getClass().getDeclaredField("began");began.setAccessible(true);began.setLong(viewer,System.nanoTime()-65_000_000_000L);assertNull(viewer.error());
            began.setLong(viewer,System.nanoTime()-121_000_000_000L);assertNotNull(viewer.error());
            viewer.accept(video(id(1),id(2),0));assertNotNull(await(viewer::pollVideo));
            Field lastVideo=viewer.getClass().getDeclaredField("lastVideo");lastVideo.setAccessible(true);lastVideo.setLong(viewer,System.nanoTime()-16_000_000_000L);
            viewer.accept(audio(id(1),id(2),0,new short[]{1,-1}));assertNotNull(await(viewer::pollAudio));assertNotNull(viewer.error());
        }
    }
}
