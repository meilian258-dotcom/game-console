package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.client.cabinet.SfcCabinetProvider;
import cn.piq.sfchome.net.*;
import cn.piq.sfchome.server.SfcRepairLedger;
import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetSyncWorker;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Actual house worker and actual generic worker in separate JVMs; platform effects are test sinks. */
public final class SfcRepairWorkerProbe implements SfcPlayback.Host {
    private static final Gson JSON=new Gson();
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private SfcPlayback playback;private CabinetSyncWorker cabinet;private Path romFile;
    private UUID lease;private int assertions,codecs,generation;private volatile int observed,audio,media,audioFlushes;
    private boolean host,quit;
    public static void main(String[] args)throws Exception{
        var p=new SfcRepairWorkerProbe();Path origin=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(SfcPlayback.class,SfcExecutionCore.class,SfcRepairLedger.class,SfcRepairNetwork.class,SfcCabinetProvider.class))p.check(origin(type).equals(origin),"SFC production origin "+type);
        p.check(origin(WasmSfcCore.class).equals(Path.of(args[1]).toRealPath()),"unchanged real WASM origin");
        p.check(origin(CabinetSyncWorker.class).equals(Path.of(args[2]).toRealPath()),"shared worker origin");
        p.check(origin(Class.forName("ai.tegmentum.wasmtime4j.Engine")).equals(Path.of(args[2]).toRealPath()),"real engine origin");
        p.emit("hello",Map.of("production_origin",origin.toString(),"minecraft_started",false));
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){String line;
            while(!p.quit&&(line=input.readLine())!=null){var c=JsonParser.parseString(line).getAsJsonObject();try{p.command(c);p.drain();
                p.emit("ack",Map.of("id",c.get("id").getAsInt(),"error",p.error(),"assertions",p.assertions,"codecs",p.codecs,"observed",p.observed,"audio_calls",p.audio,"media_calls",p.media,"audio_flushes",p.audioFlushes,"lease",SfcCoreLease.occupied()));
            }catch(Throwable t){p.emit("failure",Map.of("id",c.get("id").getAsInt(),"message",t.toString()));throw t;}}
        }finally{p.closeAll();}
    }
    private static Path origin(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private synchronized void check(boolean pass,String why){assertions++;if(!pass)throw new AssertionError(why);}
    private synchronized void emit(String event,Map<String,?> fields){var out=new LinkedHashMap<String,Object>(fields);out.put("event",event);out.put("generation",generation);System.out.println("PIQ_QA "+JSON.toJson(out));System.out.flush();}
    private <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);check(b.writerIndex()<32768,"payload bounded");T out=codec.decode(b);check(b.readableBytes()==0,"codec consumed");codecs++;return out;}finally{b.release();}}
    private static int n(JsonObject c,String name){return c.get(name).getAsInt();}
    private static int[] a(JsonObject c,String name){return JSON.fromJson(c.get(name),int[].class);}
    private static UUID token(JsonObject c){return UUID.fromString(c.get("token").getAsString());}
    private SfcRepairNetwork.Key key(JsonObject c){return new SfcRepairNetwork.Key(991,1,lease,token(c),n(c,"frame"));}
    private String error(){return playback!=null&&playback.error()!=null?playback.error():cabinet!=null&&cabinet.error()!=null?cabinet.error():"";}
    private void command(JsonObject c)throws Exception{switch(c.get("op").getAsString()){
        case "start"->{check(playback==null&&cabinet==null,"single core");generation++;host=c.get("host").getAsBoolean();observed=audio=media=audioFlushes=0;lease=UUID.randomUUID();byte[] rom=SfcTwoPortInputProbe.rom();
            var s=round(SfcHomeNetwork.Session.CODEC,new SfcHomeNetwork.Session(991,1,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),new UUID(1,1),new BlockPos(2,64,0),new UUID(2,2),new UUID(3,3),SfcExecutionCore.digest(rom),SfcHomeNetwork.CORE_BUILD,host?-1:0,lease,host));
            playback=new SfcPlayback(s,rom,new SfcStartupProgress(),this);}
        case "frames"->{int[] p1=a(c,"p1"),p2=a(c,"p2");if(cabinet==null)check(playback.offer(round(SfcHomeNetwork.Frames.CODEC,new SfcHomeNetwork.Frames(991,1,n(c,"frame"),p1,p2))),"house accepted frames");
            else{var list=new ArrayList<CabinetSyncTimeline.Step>();for(int i=0;i<p1.length;i++)list.add(new CabinetSyncTimeline.Step(n(c,"frame")+i+1,p1[i],p2[i],0,0));check(cabinet.frames(list),"generic accepted exact frames");}}
        case "checkpoint"->{var p=playback.checkpoint(n(c,"frame"));check(p!=null,"cached checkpoint exists");emit("snapshot",Map.of("frame",p.frame(),"sha",p.sha(),"bytes",Base64.getEncoder().encodeToString(p.bytes()),"size",p.bytes().length));}
        case "begin"->{var k=round(SfcRepairNetwork.Begin.CODEC,new SfcRepairNetwork.Begin(key(c))).key();playback.beginRepair(k);check(playback.synchronizationPaused(),"repair neutralizes input immediately");}
        case "restore"->{var k=key(c);byte[] state=Base64.getDecoder().decode(c.get("bytes").getAsString());String hash=c.get("sha").getAsString();var out=new ByteArrayOutputStream();
            for(int at=0;at<state.length;at+=SfcRepairLedger.CHUNK){var s=new SfcRepairNetwork.State(k,state.length,at,hash,Arrays.copyOfRange(state,at,Math.min(state.length,at+SfcRepairLedger.CHUNK)));var u=round(SfcRepairNetwork.Upload.CODEC,new SfcRepairNetwork.Upload(s));var d=round(SfcRepairNetwork.State.CODEC,u.state());check(d.offset()==out.size(),"chunk order");out.writeBytes(d.data());}
            playback.restoreRepair(k,out.toByteArray(),hash);}
        case "replay"->check(playback.replayRepair(round(SfcRepairNetwork.Replay.CODEC,new SfcRepairNetwork.Replay(key(c),a(c,"p1"),a(c,"p2")))),"repair queue accepts exact batch");
        case "resume"->playback.resumeRepair(round(SfcRepairNetwork.Resume.CODEC,new SfcRepairNetwork.Resume(key(c))).key());
        case "stale_restore"->{var k=key(c);playback.restoreRepair(k,new byte[]{1},"0".repeat(64));check(!playback.matches(991,0),"old epoch rejected");}
        case "cabinet"->{check(playback==null&&cabinet==null,"old core closed");generation++;observed=audio=media=0;host=c.get("host").getAsBoolean();byte[] rom=SfcTwoPortInputProbe.rom();romFile=Files.createTempFile("original-sfc-input-",".sfc");Files.write(romFile,rom);Path source=romFile;
            cabinet=new CabinetSyncWorker(()->new CabinetSyncWorker.Opened(new SfcCabinetProvider().openSync(source),SfcExecutionCore.digest(rom)),host);}
        case "cabinet_restore"->{byte[] state=Base64.getDecoder().decode(c.get("bytes").getAsString());cabinet.beginRestore(n(c,"frame"));cabinet.restore(token(c),n(c,"frame"),n(c,"goal"),state,c.get("sha").getAsString());}
        case "poll"->{}
        case "close"->closeAll();
        case "quit"->{closeAll();quit=true;}
        default->throw new IllegalArgumentException("test operation");}}
    private void drain(){for(Runnable r;(r=callbacks.poll())!=null;)r.run();if(cabinet!=null){CabinetSyncWorker.Event e;
        while((e=cabinet.pollEvent())!=null){switch(e.kind()){
            case CabinetSyncWorker.Event.HELLO->emit("ready",Map.of("hash",new String(e.state(),StandardCharsets.US_ASCII),"fps",e.fpsMilli()/1000.0,"compatibility",e.compatibility()));
            case CabinetSyncWorker.Event.SNAPSHOT->emit("snapshot",Map.of("frame",e.frame(),"sha",e.hash(),"bytes",Base64.getEncoder().encodeToString(e.state()),"size",e.state().length));
            case CabinetSyncWorker.Event.DIGEST->emit("digest",Map.of("frame",e.frame(),"sha",e.hash()));
            case CabinetSyncWorker.Event.RESTORED->emit("done",Map.of("frame",e.frame(),"success",true));
            default->emit("generic_event",Map.of("kind",e.kind()));}}
        var frame=cabinet.pollFrame();if(frame!=null){var b=new ByteArrayOutputStream();for(int p:frame.abgr()){b.write(p);b.write(p>>>8);b.write(p>>>16);b.write(p>>>24);}emit("picture",Map.of("video",SfcExecutionCore.digest(b.toByteArray()),"width",frame.width(),"height",frame.height(),"pcm",frame.pcm48k().length));}}
    }
    private void closeAll()throws Exception{var old=playback;playback=null;if(old!=null)old.close();if(cabinet!=null){cabinet.close();cabinet=null;}long until=System.nanoTime()+5_000_000_000L;while(SfcCoreLease.occupied()&&System.nanoTime()<until)Thread.sleep(2);check(!SfcCoreLease.occupied(),"actual core closes and releases");callbacks.clear();if(romFile!=null){Files.deleteIfExists(romFile);romFile=null;}}
    public void execute(Runnable r){callbacks.add(r);}public boolean isCurrent(SfcPlayback p){return p==playback;}
    public void ready(SfcPlayback p,SfcHomeNetwork.Ready r){emit("ready",Map.of("hash",r.initialStateHash(),"fps",r.targetFps(),"lease",lease.toString()));}
    public void captured(SfcPlayback p,SfcJoinNetwork.Capture r,byte[] bytes,String sha){}
    public void applied(SfcPlayback p,SfcJoinNetwork.Capture r,String sha,boolean success){}
    public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int length,float gain){check(length*2<=pcm.length,"full stereo audio length");audio++;}public void discardQueued(){audioFlushes++;}public void close(){}};}
    public void backup(SfcPlayback p,WasmSfcCore c,int frame){}
    public boolean consistencyChecks(){return true;}
    public void digest(SfcPlayback p,int frame,String sha){var d=round(SfcRepairNetwork.Digest.CODEC,new SfcRepairNetwork.Digest(new SfcRepairNetwork.Key(991,1,lease,SfcRepairNetwork.DIGEST_TOKEN,frame),sha));emit("digest",Map.of("frame",d.key().frame(),"sha",d.sha()));}
    public void repairedState(SfcPlayback p,SfcRepairNetwork.Key k,String sha,boolean success){var r=round(SfcRepairNetwork.Restored.CODEC,new SfcRepairNetwork.Restored(k,sha,success));emit("restored",Map.of("frame",r.key().frame(),"sha",r.sha(),"success",r.success()));}
    public void repairedFrames(SfcPlayback p,SfcRepairNetwork.Key k,boolean success){var d=round(SfcRepairNetwork.Done.CODEC,new SfcRepairNetwork.Done(k,success));emit("done",Map.of("frame",d.key().frame(),"success",d.success()));}
    public void synchronizationFault(SfcPlayback p,int frame){round(SfcRepairNetwork.Fault.CODEC,new SfcRepairNetwork.Fault(new SfcRepairNetwork.Key(991,1,lease,SfcRepairNetwork.DIGEST_TOKEN,frame)));check(p.synchronizationPaused(),"gap immediately neutralizes input");emit("fault",Map.of("frame",frame));}
    public void mediaFrame(SfcPlayback p,int w,int h,int stride,float aspect,byte[] rgba,short[] pcm,int length){media++;}
    public void observedFrame(int frame,int w,int h,byte[] rgba,short[] pcm,int length){try{var hash=MessageDigest.getInstance("SHA-256");for(int i=0;i<length*2;i++){hash.update((byte)pcm[i]);hash.update((byte)(pcm[i]>>>8));}observed=frame;emit("frame",Map.of("frame",frame,"video",SfcExecutionCore.digest(rgba),"audio",HexFormat.of().formatHex(hash.digest()),"pcm",length*2));}catch(Exception e){throw new IllegalStateException(e);}}
}
