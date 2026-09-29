package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.*;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.net.*;
import cn.piq.sfchome.server.SfcLocalWatchTransfer;
import cn.piq.sfchome.server.SfcRepairLedger;
import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** Real unmodified core9 and production receive-only worker; original diagnostic ROM, no game installation. */
public final class SfcLocalWatchWorkerProbe implements SfcPlayback.Host {
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final Map<Integer,String> video=new HashMap<>(),audio=new HashMap<>();
    private volatile SfcPlayback playback;
    private volatile int observed=600,mediaCalls,saveCalls,audioCalls;
    private String initialHash,stateHash,finalDigest,workerFailure;
    private byte[] checkpoint;
    private double fps;
    private boolean ready,restored,caughtUp;
    private int assertions,codecs;
    private final UUID lease=UUID.randomUUID();
    private SfcRepairNetwork.Key key(int frame){return new SfcRepairNetwork.Key(5535,1,lease,lease,frame);}
    private void check(boolean pass,String why){assertions++;if(!pass)throw new AssertionError(why);}
    private static int mask(int frame,int port){return frame%7==port?1<<((frame/7+port)%12):0;}
    private static String pcmHash(short[] pcm,int frames){byte[] bytes=new byte[frames*4];for(int i=0;i<frames*2;i++){bytes[2*i]=(byte)pcm[i];bytes[2*i+1]=(byte)(pcm[i]>>>8);}return SfcExecutionCore.digest(bytes);}
    private <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T packet){var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(buf,packet);check(buf.writerIndex()<32768,"bounded wire payload");T decoded=codec.decode(buf);check(buf.readableBytes()==0,"codec consumes entire payload");codecs++;return decoded;}finally{buf.release();}}
    private void pump(){for(Runnable r;(r=callbacks.poll())!=null;)r.run();if(workerFailure!=null)throw new AssertionError(workerFailure);if(playback!=null&&playback.error()!=null)throw new AssertionError(playback.error());}
    private void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+15_000_000_000L;while(!done.getAsBoolean()&&System.nanoTime()<end){pump();Thread.sleep(2);}pump();check(done.getAsBoolean(),"bounded worker completion");}
    private void reference(byte[] rom){
        try(var core=new SfcExecutionCore()){
            core.loadRom(SfcRomImage.fromBytes(rom));fps=core.initialize();initialHash=SfcExecutionCore.digest(core.saveState());byte[] rgba=new byte[0];short[] pcm=new short[4096];
            for(int f=0;f<1200;f++){
                var result=core.runFrame(new SfcControllerState(mask(f,0)),new SfcControllerState(mask(f,1)));
                if(rgba.length!=result.videoMode().requiredRgbaBytes())rgba=new byte[result.videoMode().requiredRgbaBytes()];if(pcm.length<result.requiredPcmShorts())pcm=new short[result.requiredPcmShorts()];
                core.copyRgbaFrame(rgba);int length=core.copyAudioPcm16(pcm);
                if(f>=600){video.put(f+1,SfcExecutionCore.digest(rgba));audio.put(f+1,pcmHash(pcm,length));}
                if(f==599){checkpoint=core.saveState();stateHash=SfcExecutionCore.digest(checkpoint);}
            }
            finalDigest=SfcExecutionCore.digest(core.saveState());
        }
    }
    private void offer(int first,int count){
        int[] a=new int[count],b=new int[count];for(int i=0;i<count;i++){a[i]=mask(first+i,0);b[i]=mask(first+i,1);}
        var wire=round(SfcLocalWatchNetwork.Frames.CODEC,new SfcLocalWatchNetwork.Frames(lease,new SfcRepairNetwork.Replay(key(first),a,b)));
        check(playback.offerObserved(wire.value()),"production observer queue accepts contiguous frames");
    }
    private Map<String,Object> run()throws Exception{
        byte[] rom=SfcTwoPortInputProbe.rom();reference(rom);
        var session=new SfcHomeNetwork.Session(5535,1,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),UUID.randomUUID(),new BlockPos(2,64,0),UUID.randomUUID(),UUID.randomUUID(),SfcExecutionCore.digest(rom),SfcHomeNetwork.CORE_BUILD,0,lease,false,1,UUID.randomUUID(),UUID.randomUUID());
        var start=round(SfcLocalWatchNetwork.Start.CODEC,new SfcLocalWatchNetwork.Start(1,lease,session,600,stateHash,initialHash,fps,20));
        check(start.exitRange()==20&&!start.session().executionHost(),"read-only start range and role");
        check(round(SfcLocalWatchNetwork.Preference.CODEC,new SfcLocalWatchNetwork.Preference(1,1,true)).mode()==1,"explicit local preference");
        var capture=round(SfcLocalWatchNetwork.Capture.CODEC,new SfcLocalWatchNetwork.Capture(key(600)));
        check(round(SfcLocalWatchNetwork.CaptureFailed.CODEC,new SfcLocalWatchNetwork.CaptureFailed(capture.key())).key().equals(key(600)),"capture failure exact key");
        // Host pin remains valid after its two-slot checkpoint cache evicts frame 600.
        var cache=new SfcCheckpoints();cache.put(600,checkpoint);var pinned=cache.get(600);cache.put(1200,new byte[]{2});cache.put(1800,new byte[]{3});
        check(cache.get(600)==null&&pinned!=null&&pinned.sha().equals(stateHash),"pinned snapshot survives source cache eviction");
        var server=new SfcLocalWatchTransfer(checkpoint.length,stateHash);
        for(int at=0;at<checkpoint.length;at+=SfcRepairLedger.CHUNK){int end=Math.min(checkpoint.length,at+SfcRepairLedger.CHUNK);
            var p=round(SfcLocalWatchNetwork.Upload.CODEC,new SfcLocalWatchNetwork.Upload(new SfcRepairNetwork.State(key(600),checkpoint.length,at,stateHash,Arrays.copyOfRange(pinned.bytes(),at,end))));
            check(server.append(p.value().total(),p.value().offset(),p.value().sha(),p.value().data()),"ordered host upload validated");
        }
        check(server.complete(),"snapshot upload complete before observer core ready");
        try{
            playback=new SfcPlayback(start.session(),rom,new SfcStartupProgress(),this);playback.beginRepair(key(600));await(()->ready);
            check(SfcCoreLease.observing(),"core belongs to read-only observer");
            var client=new SfcLocalWatchTransfer(checkpoint.length,stateHash);byte[] forwarded=server.take();
            for(int at=0;at<forwarded.length;at+=SfcRepairLedger.CHUNK){int end=Math.min(forwarded.length,at+SfcRepairLedger.CHUNK);
                var p=round(SfcLocalWatchNetwork.State.CODEC,new SfcLocalWatchNetwork.State(new SfcRepairNetwork.State(key(600),forwarded.length,at,stateHash,Arrays.copyOfRange(forwarded,at,end))));
                check(client.append(p.value().total(),p.value().offset(),p.value().sha(),p.value().data()),"client revalidates ordered state");
            }
            playback.restoreRepair(key(600),client.take(),stateHash);await(()->restored);
            offer(600,240);offer(840,60);playback.resumeRepair(round(SfcLocalWatchNetwork.Resume.CODEC,new SfcLocalWatchNetwork.Resume(key(900))).key());await(()->caughtUp&&observed==900);
            for(int first=900;first<1200;first+=3){offer(first,3);int target=first+3;await(()->observed>=target);}
            await(()->observed==1200);pump();
            check(mediaCalls==0&&saveCalls==0,"observer never publishes media or writes saves");check(audioCalls>0,"live observer produces local audio after catch-up");
            round(SfcLocalWatchNetwork.Cancel.CODEC,new SfcLocalWatchNetwork.Cancel(lease));round(SfcLocalWatchNetwork.Stopped.CODEC,new SfcLocalWatchNetwork.Stopped(1,lease,""));
            var previous=playback;playback=null;previous.close();await(()->!SfcCoreLease.occupied());
            previous.restoreRepair(key(600),checkpoint,stateHash);check(!previous.offerObserved(new SfcRepairNetwork.Replay(key(1200),new int[]{0},new int[]{0})),"closed lease rejects queued work");
            try(var active=SfcCoreLease.acquire()){check(!SfcCoreLease.observing(),"controller acquires only after observer closed");}
            return Map.of("ok",true,"assertions",assertions,"codec_roundtrips",codecs,"rgba_pcm_frames_compared",600,"checkpoint_bytes",checkpoint.length,"local_audio_frames",audioCalls,"observer_saves",saveCalls,"observer_media_uploads",mediaCalls,"actual_wasm",true);
        }finally{if(playback!=null){playback.close();await(()->!SfcCoreLease.occupied());}}
    }
    private static Path origin(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    public static void main(String[] args)throws Exception{Path sfc=Path.of(args[0]).toRealPath(),core=Path.of(args[1]).toRealPath();for(var c:List.of(SfcPlayback.class,SfcLocalWatchNetwork.class,SfcLocalWatchTransfer.class))if(!origin(c).equals(sfc))throw new AssertionError("unexpected production origin "+c);if(!origin(WasmSfcCore.class).equals(core))throw new AssertionError("unexpected WASM origin");System.out.println("PIQ_WATCH_QA "+new Gson().toJson(new SfcLocalWatchWorkerProbe().run()));}
    public void execute(Runnable r){callbacks.add(r);}public boolean isCurrent(SfcPlayback p){return p==playback;}public boolean readOnlyObserver(){return true;}public boolean consistencyChecks(){return true;}
    public void ready(SfcPlayback p,SfcHomeNetwork.Ready r){var packet=round(SfcLocalWatchNetwork.Ready.CODEC,new SfcLocalWatchNetwork.Ready(lease,r.initialStateHash(),r.targetFps()));check(packet.initial().equals(initialHash)&&Math.abs(packet.fps()-fps)<.001,"matching ROM/core init");ready=true;}
    public void captured(SfcPlayback p,SfcJoinNetwork.Capture r,byte[] bytes,String hash){throw new AssertionError("observer was granted capture");}public void applied(SfcPlayback p,SfcJoinNetwork.Capture r,String hash,boolean ok){throw new AssertionError("observer was granted controller join");}
    public void backup(SfcPlayback p,WasmSfcCore core,int frame){saveCalls++;throw new AssertionError("observer attempted save");}public void mediaFrame(SfcPlayback p,int w,int h,int stride,float aspect,byte[] rgba,short[] pcm,int frames){mediaCalls++;}
    public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int frames,float gain){audioCalls++;}public void close(){}};}
    public void repairedState(SfcPlayback p,SfcRepairNetwork.Key k,String sha,boolean ok){check(ok&&sha.equals(stateHash)&&k.equals(key(600)),"restored state exact");round(SfcLocalWatchNetwork.Ack.CODEC,new SfcLocalWatchNetwork.Ack(lease,0,true));restored=true;}
    public void repairedFrames(SfcPlayback p,SfcRepairNetwork.Key k,boolean ok){check(ok&&k.frame()==900,"catch-up exact boundary");round(SfcLocalWatchNetwork.Ack.CODEC,new SfcLocalWatchNetwork.Ack(lease,1,true));caughtUp=true;}
    public void digest(SfcPlayback p,int frame,String sha){var d=round(SfcLocalWatchNetwork.Digest.CODEC,new SfcLocalWatchNetwork.Digest(lease,frame,sha));check(frame==1200&&d.sha().equals(finalDigest),"exact live final core state");}
    public void synchronizationFault(SfcPlayback p,int frame){workerFailure="observer input fault at "+frame;}
    public void observedFrame(int frame,int width,int height,byte[] rgba,short[] pcm,int frames){if(frame!=observed+1||!video.get(frame).equals(SfcExecutionCore.digest(rgba))||!audio.get(frame).equals(pcmHash(pcm,frames)))workerFailure="RGBA/PCM/order differs at "+frame;observed=frame;}
}
