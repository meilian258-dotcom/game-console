package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.retro.api.RetroFrame;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** No Minecraft API or socket on the worker. Both directions have bounded queues and lifetime. */
final class CabinetMediaStream implements AutoCloseable {
    private final UUID room,host;
    private final boolean sender;
    private final Runnable receivedFrameMeter;
    private final ArrayBlockingQueue<CabinetMediaPacket> inbound=new ArrayBlockingQueue<>(32);
    private final ArrayBlockingQueue<List<CabinetMediaPacket>> outbound=new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<short[]> audio=new ArrayBlockingQueue<>(6);
    private final AtomicReference<RetroFrame> latest=new AtomicReference<>(),received=new AtomicReference<>();
    private final CabinetPcmBuffer pcm=new CabinetPcmBuffer();
    private final CabinetMediaAssembler assembler=new CabinetMediaAssembler();
    private final Thread worker;
    private volatile boolean closed,sending;
    private volatile String error;
    private volatile long lastReceived=System.nanoTime();
    private volatile long lastVideo=System.nanoTime();
    private volatile boolean receivedVideo;
    private final long startupGrace;
    private long videoSequence,sendAt,refillAt=System.nanoTime(),audioEnd=-1;
    private double budget=262144;
    private final CabinetMediaPolicy policy=new CabinetMediaPolicy();
    private final AtomicLong offered=new AtomicLong(),encodedCount=new AtomicLong(),displayed=new AtomicLong(),accepted=new AtomicLong(),rejected=new AtomicLong(),sentBytes=new AtomicLong(),dropped=new AtomicLong();
    private final long began=System.nanoTime();
    private long audioAt,sampleAt,previousRejected;
    private double meanSize,meanEncode;
    private volatile int targetFps=20;
    private volatile double lastEncodeMs;
    CabinetMediaStream(UUID room,UUID host,boolean sender){
        this(room,host,sender,false);
    }
    CabinetMediaStream(UUID room,UUID host,boolean sender,boolean serverHosted){
        this.room=room;this.host=host;this.sender=sender;
        receivedFrameMeter=cn.piq.fcarcade.network.ModTrafficProbe.videoMeter(room);
        startupGrace=serverHosted?120_000_000_000L:15_000_000_000L;
        worker=Thread.ofPlatform().daemon(true).name("PIQ-Cabinet-Network-CabinetMediaPacket").start(this::run);
        CabinetMediaTuning.register(this);
    }
    void sending(boolean enabled){sending=enabled;if(!enabled){latest.set(null);pcm.clear();outbound.clear();}}
    void offer(RetroFrame frame){if(!closed&&sender&&sending){offered.incrementAndGet();if(latest.getAndSet(frame)!=null)dropped.incrementAndGet();pcm.offer(frame.pcm48k());}}
    void accept(CabinetMediaPacket part){
        if(closed||sender||!room.equals(part.room())||!host.equals(part.hostMember()))return;
        if(!inbound.offer(part)){error="街机音画接收过慢，请重新加入";return;}
        lastReceived=System.nanoTime();
    }
    List<CabinetMediaPacket> pollOutbound(){return outbound.poll();}
    RetroFrame pollVideo(){var frame=received.getAndSet(null);if(frame!=null)displayed.incrementAndGet();return frame;}
    void transportResult(List<CabinetMediaPacket> batch,boolean admitted){if(batch==null||batch.isEmpty())return;if(admitted){if(batch.getFirst().kind()==0)accepted.incrementAndGet();for(var p:batch)sentBytes.addAndGet(p.data().length);}else{rejected.incrementAndGet();dropped.incrementAndGet();}}
    List<String> diagnostics(){double seconds=Math.max(1,(System.nanoTime()-began)/1e9);return sender?List.of(
        String.format(java.util.Locale.ROOT,"发送：目标 %d 帧 · 取帧 %.1f / 编码 %.1f 帧每秒",targetFps,offered.get()/seconds,encodedCount.get()/seconds),
        String.format(java.util.Locale.ROOT,"队列接纳 %.1f 帧/秒 · %.0f KiB/秒 · 编码 %.1f ms",accepted.get()/seconds,sentBytes.get()/seconds/1024,lastEncodeMs),
        "待发 "+outbound.size()+" / 8 · 合帧/丢帧 "+dropped.get()+" · 网络拒收 "+rejected.get()):List.of(
        String.format(java.util.Locale.ROOT,"接收：显示取帧 %.1f 帧/秒 · 接收队列 %d / 32",displayed.get()/seconds,inbound.size()));}
    short[] pollAudio(){return audio.poll();}
    String error(){long now=System.nanoTime();if(!sender&&(!receivedVideo?now-began>startupGrace:now-lastReceived>15_000_000_000L||now-lastVideo>15_000_000_000L))return "街机画面来源超时，已退出席位";return error;}
    private void run(){
        try{while(!closed){
            if(sender){encode(System.nanoTime());TimeUnit.MILLISECONDS.sleep(5);}
            else{CabinetMediaPacket p=inbound.poll(100,TimeUnit.MILLISECONDS);if(p!=null)decode(p);}
        }}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception|LinkageError failure){if(!closed)error="街机音画处理失败："+failure.getClass().getSimpleName();}
    }
    private void encode(long now){
        if(!sending)return;
        if(now>=sampleAt){sampleAt=now+1_000_000_000L;var quality=CabinetMediaTuning.quality();long refused=rejected.get();
            targetFps=policy.sample(quality==CabinetMediaTuning.Quality.AUTO,quality==CabinetMediaTuning.Quality.FPS30?30:20,meanSize,meanEncode,outbound.size(),refused-previousRejected);previousRejected=refused;}
        CabinetPcmBuffer.Chunk chunk=null;if(now>=audioAt){audioAt=now+50_000_000L;chunk=pcm.poll(4800);} // Audio cadence is independent of video fps.
        if(chunk!=null){byte[] data=CabinetMediaCodec.encodePcm(chunk.samples(),0,chunk.samples().length);
            if(reserve(data.length,1,now))outbound.offer(List.of(new CabinetMediaPacket(room,host,chunk.firstSample(),1,0,1,0,0,1F,0,data.length,data)));}
        if(now<sendAt)return;sendAt=now+1_000_000_000L/targetFps;
        RetroFrame frame=latest.getAndSet(null);if(frame==null)return;
        long started=System.nanoTime();var encoded=CabinetMediaCodec.encodeVideo(frame);lastEncodeMs=(System.nanoTime()-started)/1e6;if(encoded==null){dropped.incrementAndGet();return;}
        encodedCount.incrementAndGet();meanEncode=meanEncode==0?lastEncodeMs:.9*meanEncode+.1*lastEncodeMs;
        byte[] data=encoded.data();int count=(data.length+24575)/24576;
        meanSize=meanSize==0?data.length:.9*meanSize+.1*data.length;
        long sequence=videoSequence++;
        if(!reserve(data.length+9600,count,now)){dropped.incrementAndGet();return;}budget+=9600; // Keep one audio block of headroom.
        List<CabinetMediaPacket> batch=new ArrayList<>(count);
        for(int i=0;i<count;i++)batch.add(new CabinetMediaPacket(room,host,sequence,0,i,count,encoded.width(),encoded.height(),encoded.displayAspect(),encoded.rotation(),encoded.width()*encoded.height()*2,Arrays.copyOfRange(data,i*24576,Math.min(data.length,(i+1)*24576))));
        if(!outbound.offer(List.copyOf(batch)))dropped.incrementAndGet();
    }
    private boolean reserve(int length,int packets,long now){
        budget=Math.min(262144,budget+Math.max(0,now-refillAt)/1e9*1048576);refillAt=now;
        if(outbound.remainingCapacity()<1||budget<length)return false;
        budget-=length;return true;
    }
    private void decode(CabinetMediaPacket part){
        var complete=assembler.accept(part,System.nanoTime());if(complete==null)return;
        CabinetMediaPacket h=complete.header();
        if(h.kind()==0){var encoded=new CabinetMediaCodec.Encoded(h.width(),h.height(),h.aspect(),h.rotation(),complete.bytes());
            var frame=new RetroFrame(h.width(),h.height(),CabinetMediaCodec.decodeVideo(encoded),h.aspect(),h.rotation(),new short[0]);
            lastVideo=System.nanoTime();receivedVideo=true;received.set(frame);receivedFrameMeter.run();}
        else{short[] samples=CabinetMediaCodec.decodePcm(complete.bytes());
            if(audioEnd>=0&&h.sequence()!=audioEnd)audio.clear();
            audioEnd=h.sequence()+samples.length/2;
            if(!audio.offer(samples)){audio.poll();audio.offer(samples);}
        }
    }
    @Override public void close(){closed=true;CabinetMediaTuning.remove(this);worker.interrupt();inbound.clear();outbound.clear();audio.clear();latest.set(null);received.set(null);pcm.clear();}
}
