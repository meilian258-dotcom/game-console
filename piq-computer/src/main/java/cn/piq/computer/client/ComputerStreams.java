package cn.piq.computer.client;

import cn.piq.computer.net.ComputerNetwork;
import cn.piq.computer.net.ComputerStreamNetwork;
import cn.piq.computer.net.ComputerStreamNetwork.*;
import cn.piq.computer.stream.*;
import cn.piq.computer.world.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-thread session control; JPEG and audio output never run on the render/server thread. */
public final class ComputerStreams implements ComputerStreamNetwork.Client {
    private static final Map<UUID,View> VIEWS=new HashMap<>();
    private static final ExecutorService CODEC=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"Computer-Stream-Codec");t.setDaemon(true);return t;});
    private static Host host;private static Key pending;private static long requested;private static Object connection;
    private static String message="串流未开启";
    private static Minecraft mc(){return Minecraft.getInstance();}
    private static UUID me(){return mc().player==null?ComputerStreamNetwork.NONE:mc().player.getUUID();}
    private static void action(Key key,int op,UUID session,int kind,int tier){if(mc().getConnection()!=null)PacketDistributor.sendToServer(new Action(key,op,session,kind,tier));}
    public boolean accepts(Object c){return mc().getConnection()!=null&&mc().getConnection().getConnection()==c;}
    public static ComputerEntity current(Key key){var m=mc();if(m.level==null||m.player==null||!m.level.dimension().location().equals(key.dimension())||!m.level.hasChunkAt(key.pos())||m.player.distanceToSqr(key.pos().getCenter())>1024)return null;var p=ComputerBlock.find(m.level,key.pos());return p!=null&&p.hardwareId().equals(key.computer())?p:null;}
    public static boolean remote(UUID id){return VIEWS.containsKey(id);}
    public static boolean shared(UUID id){return remote(id)||host!=null&&host.status.key().computer().equals(id)||pending!=null&&pending.computer().equals(id);}
    public static boolean hosting(){return host!=null||pending!=null;}
    public static boolean playing(){return host!=null&&!host.status.controller().equals(ComputerStreamNetwork.NONE);}
    public static void start(ComputerNetwork.Open open,int kind,int tier){stopHost();pending=new Key(open.dimension(),open.pos(),open.id());requested=System.nanoTime();connection=mc().getConnection();message="等待服务器开启共享…";action(pending,0,ComputerStreamNetwork.NONE,kind,tier);}
    public static void stopHost(){var h=host;host=null;pending=null;if(h!=null){h.closed=true;action(h.status.key(),1,h.status.session(),0,0);}var backend=ComputerPrograms.backend();if(backend!=null)backend.audioSink(null);}
    public static boolean isHost(UUID id){return host!=null&&host.status.key().computer().equals(id);}
    public static boolean handoff(){return host!=null&&host.status.handoff();}
    public static void handoff(boolean allow){if(host!=null)action(host.status.key(),allow?2:3,host.status.session(),0,0);}
    public void status(Status s){
        if(!s.active()){
            if(host!=null&&host.status.session().equals(s.session())){stopHost();ComputerPrograms.stop();message="共享结束";}
            var v=VIEWS.get(s.key().computer());if(v!=null&&v.status.session().equals(s.session())){VIEWS.remove(s.key().computer());v.close();}return;
        }
        if(current(s.key())==null)return;connection=mc().getConnection();
        if(s.host().equals(me())){
            if(host==null){if(pending==null||!pending.equals(s.key())){action(s.key(),1,s.session(),0,0);return;}host=new Host(s);pending=null;}
            if(!host.status.session().equals(s.session()))return;
            if(s.epoch()>host.epoch){host.epoch=s.epoch();var b=ComputerPrograms.backend();if(b!=null)b.releaseInput();}
            if(s.epoch()>=host.status.epoch())host.status=s;host.seen=System.nanoTime();message="共享运行中";return;
        }
        var v=VIEWS.get(s.key().computer());
        if(v!=null&&!v.status.session().equals(s.session())){v.close();VIEWS.remove(s.key().computer());v=null;}
        if(v==null){if(VIEWS.size()>=8)return;v=new View(s);VIEWS.put(s.key().computer(),v);}
        if(s.epoch()>=v.status.epoch())v.status=s;v.seen=System.nanoTime();
    }
    public void input(Input i){var h=host;var b=ComputerPrograms.backend();if(h==null||b==null||!i.computer().equals(h.status.key().computer())||!i.session().equals(h.status.session())||i.epoch()<h.epoch)return;
        if(i.epoch()>h.epoch){h.epoch=i.epoch();b.releaseInput();}
        switch(i.kind()){case 0->b.pointer(i.x(),i.y(),i.buttons()&7,(i.buttons()&8)==0);case 1->{if(i.value()<=512)b.key(i.value(),true);}case 2->{if(i.value()<=512)b.key(i.value(),false);}case 3->b.text(i.value());case 5->b.releaseInput();default->{}}
    }
    public void media(Media media){var p=media.part();var v=VIEWS.get(p.computer());if(v==null||!v.status.session().equals(p.session()))return;long now=System.nanoTime();
        v.bytes+=p.bytes().length+96;
        if(p.kind()==1){if(p.sequence()<=v.lastAudio)return;v.lastAudio=p.sequence();if(v.fresh(p.micros(),now))v.speaker.offer(p.bytes());return;}
        var jpeg=v.assembler.accept(p,now);if(jpeg==null||!v.fresh(p.micros(),now))return;
        if(!v.decoding.compareAndSet(false,true)){v.dropped++;return;}
        CODEC.execute(()->{try{if(!v.closed)v.frame.set(StreamImage.decode(jpeg));}catch(Exception e){v.dropped++;}finally{v.decoding.set(false);}});
    }
    public static void frame(byte[] rgba,int width,int height){var h=host;if(h==null)return;long now=System.nanoTime();if(now-h.lastFrame<(h.status.viewers()==0?1_000_000_000L:66_666_667L)||!h.encoding.compareAndSet(false,true))return;h.lastFrame=now;var copy=rgba.clone();
        CODEC.execute(()->{try{var bytes=h.encoder.encode(copy,width,height);if(!h.closed&&bytes!=null){h.picture=bytes;h.video.set(new Encoded(bytes,now/1000));}else h.dropped++;}catch(Exception e){h.dropped++;}finally{h.encoding.set(false);}});
    }
    public static void attach(ProgramBackend backend){var h=host;if(h==null||h.attached==backend)return;h.attached=backend;backend.audioSink((bytes,rate,channels)->h.audio(bytes,rate,channels));}
    public static ResourceLocation texture(ComputerEntity pc){var v=VIEWS.get(pc.hardwareId());if(v==null)return null;var frame=v.frame.getAndSet(null);if(frame!=null){
        if(v.texture==null){v.texture=new DynamicTexture(640,480,false);v.texture.setFilter(false,false);v.textureId=mc().getTextureManager().register("computer_stream",v.texture);v.pixels=org.lwjgl.system.MemoryUtil.memAlloc(640*480*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);}
        v.pixels.clear();v.pixels.asIntBuffer().put(frame);
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(v.texture.getId());
        com.mojang.blaze3d.platform.GlStateManager._pixelStore(3314,0);com.mojang.blaze3d.platform.GlStateManager._pixelStore(3316,0);com.mojang.blaze3d.platform.GlStateManager._pixelStore(3315,0);com.mojang.blaze3d.platform.GlStateManager._pixelStore(3317,4);
        com.mojang.blaze3d.platform.GlStateManager._texSubImage2D(3553,0,0,0,640,480,6408,5121,org.lwjgl.system.MemoryUtil.memAddress(v.pixels));v.frames++;
    }return v.textureId;}
    public static String status(UUID id){var h=host;if(h!=null&&h.status.key().computer().equals(id))return "共享 · 旁观 "+h.status.viewers()+" · 上传估算 "+h.rate+" KiB/s · 总计 "+(h.bytes/1024)+" KiB · 丢帧 "+h.dropped;
        var v=VIEWS.get(id);return v==null?message:"旁观 · 下载估算 "+v.rate+" KiB/s · 总计 "+(v.bytes/1024)+" KiB · "+(v.speaker.error.isEmpty()?"24kHz 单声道":v.speaker.error);}
    public static void tick(){long now=System.nanoTime();if(connection!=null&&mc().getConnection()!=connection){clear();return;}
        if(pending!=null&&now-requested>5_000_000_000L){pending=null;ComputerPrograms.stop();message="共享未获授权，请重新右键键鼠启动";say(message);}
        var h=host;if(h!=null){if(current(h.status.key())==null||now-h.seen>5_000_000_000L){ComputerPrograms.stop();return;}if(now-h.heartbeat>1_000_000_000L){action(h.status.key(),4,h.status.session(),0,0);h.heartbeat=now;}
            if(h.status.viewers()>0){for(int n=0;n<4;n++){var a=h.audio.poll();if(a==null)break;if(now/1000-a.micros<200_000)h.send(a.bytes,1,a.micros,now);}
                var image=h.video.getAndSet(null);if(image!=null&&now/1000-image.micros<250_000)h.send(image.bytes,0,image.micros,now);
                else if(h.picture!=null&&now-h.lastSent>2_000_000_000L)h.send(h.picture,0,now/1000,now); // independent keyframe for late joiners even while paused
            }else{h.audio.clear();h.video.set(null);}h.measure(now);
        }
        for(var it=VIEWS.values().iterator();it.hasNext();){var v=it.next();var p=current(v.status.key());if(p==null||!p.powered||now-v.seen>4_000_000_000L){v.close();it.remove();continue;}double distance=mc().player.distanceToSqr(p.getBlockPos().getCenter());v.speaker.volume=mc().options.getSoundSourceVolume(SoundSource.MASTER)*mc().options.getSoundSourceVolume(SoundSource.BLOCKS)*(float)Math.max(0,1-Math.sqrt(distance)/32)*.7f;v.measure(now);}
    }
    public static void clear(){stopHost();for(var v:VIEWS.values())v.close();VIEWS.clear();connection=null;}
    private static void say(String text){if(mc().player!=null)mc().player.displayClientMessage(Component.literal(text),false);}
    private record Encoded(byte[] bytes,long micros){}
    private abstract static class Meter {long bytes,previous,window=System.nanoTime(),rate;void measure(long now){if(now-window>=1_000_000_000L){rate=Math.round((bytes-previous)*1e9/(now-window)/1024);previous=bytes;window=now;}}}
    private static final class Host extends Meter {
        Status status;long epoch,seen=System.nanoTime(),heartbeat,lastFrame,lastSent,videoSeq,audioSeq;volatile long dropped;volatile boolean closed;volatile byte[] picture;ProgramBackend attached;
        final StreamBudget budget;final AtomicBoolean encoding=new AtomicBoolean();final AtomicReference<Encoded> video=new AtomicReference<>();final ArrayBlockingQueue<Encoded> audio=new ArrayBlockingQueue<>(4);final StreamAudio.Resampler resampler=new StreamAudio.Resampler();
        final StreamImage.Encoder encoder;
        Host(Status s){status=s;epoch=s.epoch();budget=new StreamBudget(s.tier(),System.nanoTime());encoder=new StreamImage.Encoder(StreamImage.frameBudget(s.tier()));}
        synchronized void audio(byte[] pcm,int rate,int channels){if(closed)return;var bytes=resampler.convert(pcm,rate,channels);long now=System.nanoTime()/1000;for(int i=0;i<bytes.length;i+=1200){var e=new Encoded(Arrays.copyOfRange(bytes,i,Math.min(i+1200,bytes.length)),now);if(!audio.offer(e)){audio.poll();audio.offer(e);}}}
        void send(byte[] bytes,int kind,long micros,long now){int count=(bytes.length+StreamPart.PART-1)/StreamPart.PART;if(!budget.take(bytes.length+count*96,now)){if(kind==0)dropped++;return;}if(kind==0)lastSent=now;long seq=kind==0?videoSeq++:audioSeq++;for(int i=0;i<count;i++){var b=Arrays.copyOfRange(bytes,i*StreamPart.PART,Math.min((i+1)*StreamPart.PART,bytes.length));PacketDistributor.sendToServer(new Media(new StreamPart(status.key().computer(),status.session(),seq,micros,kind,i,count,kind==0?640:0,kind==0?480:0,b)));this.bytes+=b.length+96;}}
    }
    private static final class View extends Meter implements AutoCloseable {
        Status status;long seen=System.nanoTime(),lastAudio=-1,frames,clockOffset=Long.MIN_VALUE,clockWindow=System.nanoTime(),windowOffset=Long.MAX_VALUE;volatile long dropped;volatile boolean closed;
        final StreamAssembler assembler;final StreamSpeaker speaker=new StreamSpeaker();final AtomicBoolean decoding=new AtomicBoolean();final AtomicReference<int[]> frame=new AtomicReference<>();DynamicTexture texture;ResourceLocation textureId;java.nio.ByteBuffer pixels;
        View(Status s){status=s;assembler=new StreamAssembler(s.key().computer(),s.session());}
        boolean fresh(long micros,long now){long offset=now/1000-micros;windowOffset=Math.min(windowOffset,offset);if(now-clockWindow>=5_000_000_000L){clockOffset=windowOffset;windowOffset=Long.MAX_VALUE;clockWindow=now;}if(clockOffset==Long.MIN_VALUE||offset<clockOffset)clockOffset=offset;return offset-clockOffset<250_000;}
        public void close(){closed=true;speaker.close();frame.set(null);if(textureId!=null)mc().getTextureManager().release(textureId);else if(texture!=null)texture.close();if(pixels!=null){org.lwjgl.system.MemoryUtil.memFree(pixels);pixels=null;}}
    }
}
