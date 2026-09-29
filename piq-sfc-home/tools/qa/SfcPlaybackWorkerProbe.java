// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.net.SfcJoinNetwork;
import cn.piq.sfchome.server.SfcInputTimeline;
import cn.piq.sfchome.server.SfcJoinGate;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Pipe-controlled real Playback worker. No Minecraft client, audio device or network socket. */
public final class SfcPlaybackWorkerProbe implements SfcPlayback.Host {
    private static final Gson JSON=new Gson();
    private static final UUID HOST=new UUID(1,1),GUEST=new UUID(2,2),CONSOLE=new UUID(3,3);
    private final ConcurrentLinkedQueue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private final Set<UUID> cancelled=new HashSet<>();
    private final Map<UUID,SfcJoinGate> gates=new HashMap<>();
    private SfcPlayback playback;
    private volatile SfcPlayback current;
    private int assertions,codecs,generation;
    private volatile int observed,audioCalls,backups,mediaCalls;
    private int epoch=1,runtimePort;
    private boolean executionHost;
    private volatile String backupSha="";
    private UUID lease;
    private boolean quit;

    public static void main(String[] args)throws Exception {
        SfcPlaybackWorkerProbe probe=new SfcPlaybackWorkerProbe();
        Path expected=Path.of(args[0]).toRealPath();
        probe.check(origin(SfcPlayback.class).equals(expected),"Playback origin");
        for(Class<?> type:List.of(SfcJoinGate.class,SfcInputTimeline.class,SfcHomeNetwork.Session.class,SfcJoinNetwork.ControllerReady.class,SfcJoinNetwork.ControllerLeave.class))
            probe.check(origin(type).equals(expected),"production gate/protocol origin: "+type.getName());
        probe.check(origin(WasmSfcCore.class).equals(Path.of(args[1]).toRealPath()),"Actual WASM origin");
        probe.check(origin(Class.forName("ai.tegmentum.wasmtime4j.Engine")).equals(Path.of(args[2]).toRealPath()),"Embedded WASM engine origin");
        probe.emit("hello",Map.of("playback_origin",expected.toString(),"core_origin",origin(WasmSfcCore.class).toString(),
                "minecraft_started",false,"audio_device_opened",false));
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            String line;while(!probe.quit&&(line=in.readLine())!=null){
                JsonObject command=JsonParser.parseString(line).getAsJsonObject();
                try{
                    probe.command(command);
                    if(!command.has("drain")||command.get("drain").getAsBoolean())probe.drain();
                    probe.emit("ack",Map.of("id",command.get("id").getAsInt(),"assertions",probe.assertions,"codecs",probe.codecs,
                            "observed",probe.observed,"error",probe.playback==null||probe.playback.error()==null?"":probe.playback.error(),
                            "callbacks",probe.callbacks.size(),"lease",SfcCoreLease.occupied(),"audio_calls",probe.audioCalls,"backups",probe.backups,"media_calls",probe.mediaCalls));
                }catch(Throwable failure){
                    probe.emit("failure",Map.of("id",command.get("id").getAsInt(),"message",failure.toString()));
                    failure.printStackTrace(System.err);throw failure;
                }
            }
        }finally{probe.closePlayback();}
    }
    private static Path origin(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private synchronized void emit(String kind,Map<String,?> fields){
        Map<String,Object> out=new LinkedHashMap<>(fields);out.put("event",kind);out.put("generation",generation);
        System.out.println("PIQ_QA "+JSON.toJson(out));System.out.flush();
    }
    private <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        RegistryFriendlyByteBuf buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(buffer,value);T result=codec.decode(buffer);check(buffer.readableBytes()==0,"codec consumed all bytes");codecs++;return result;}
        finally{buffer.release();}
    }
    private void drain(){for(Runnable action;(action=callbacks.poll())!=null;)action.run();}
    private static int number(JsonObject command,String name){return command.get(name).getAsInt();}
    private static int[] masks(JsonObject command,String name){return JSON.fromJson(command.get(name),int[].class);}
    private UUID token(JsonObject command){return UUID.fromString(command.get("token").getAsString());}
    private SfcJoinNetwork.Capture request(JsonObject command){return round(SfcJoinNetwork.Capture.CODEC,
            new SfcJoinNetwork.Capture(7001,epoch,token(command),number(command,"frame")));}

    private void command(JsonObject c)throws Exception{
        switch(c.get("op").getAsString()){
            case "start" -> {
                check(playback==null,"previous playback closed");generation++;observed=audioCalls=backups=mediaCalls=0;backupSha="";
                epoch=c.has("epoch")?number(c,"epoch"):1;runtimePort=number(c,"port");executionHost=c.has("execution_host")?c.get("execution_host").getAsBoolean():runtimePort==0;
                lease=UUID.randomUUID();byte[] rom=SfcTwoPortInputProbe.rom();
                SfcHomeNetwork.Session configured;
                if(c.has("execution_host"))configured=SfcHomeNetwork.Session.class.getConstructor(long.class,int.class,ResourceLocation.class,BlockPos.class,UUID.class,BlockPos.class,UUID.class,UUID.class,String.class,String.class,int.class,UUID.class,boolean.class).newInstance(7001L,epoch,
                        ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),CONSOLE,new BlockPos(1,64,0),new UUID(4,4),new UUID(5,5),SfcJoinGate.sha(rom),SfcHomeNetwork.CORE_BUILD,runtimePort,lease,executionHost);
                else configured=new SfcHomeNetwork.Session(7001,epoch,
                        ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),CONSOLE,
                        new BlockPos(1,64,0),new UUID(4,4),new UUID(5,5),SfcJoinGate.sha(rom),SfcHomeNetwork.CORE_BUILD,runtimePort,lease);
                SfcHomeNetwork.Session session=round(SfcHomeNetwork.Session.CODEC,configured);
                check(session.port()==runtimePort,"runtime port survives actual codec");
                if(c.has("execution_host"))check((boolean)session.getClass().getMethod("executionHost").invoke(session)==executionHost,"host authority survives actual codec");
                playback=new SfcPlayback(session,rom,new SfcStartupProgress(),this);current=playback;
            }
            case "frames" -> {
                int[] p1=masks(c,"p1"),p2=masks(c,"p2");
                SfcHomeNetwork.Frames f=round(SfcHomeNetwork.Frames.CODEC,new SfcHomeNetwork.Frames(7001,epoch,number(c,"frame"),p1,p2));
                check(Arrays.equals(p1,f.p1Masks())&&Arrays.equals(p2,f.p2Masks()),"frame codec masks");
                check(playback.offer(f),"real worker accepted frame batch");
            }
            case "capture" -> {
                var r=request(c);cancelled.remove(r.token());
                SfcJoinGate gate=new SfcJoinGate(r.token(),HOST,GUEST,lease,CONSOLE,100);
                var approval=round(SfcJoinNetwork.Approval.CODEC,new SfcJoinNetwork.Approval(7001,epoch,r.token(),"diagnostic P2"));
                var decision=round(SfcJoinNetwork.Decision.CODEC,new SfcJoinNetwork.Decision(7001,epoch,approval.token(),true));
                check(!gate.approve(GUEST,r.token(),true,101),"candidate cannot approve itself");
                check(gate.approve(HOST,decision.token(),decision.accepted(),101),"host consent accepted");
                check(gate.capture(GUEST,r.frame(),102),"loading candidate requests boundary");
                gates.put(r.token(),gate);playback.capture(r);
            }
            case "restore" -> {
                var r=request(c);byte[] bytes=Base64.getDecoder().decode(c.get("bytes").getAsString());String sha=c.get("sha").getAsString();
                ByteArrayOutputStream received=new ByteArrayOutputStream();
                for(int off=0;off<bytes.length;off+=SfcJoinGate.CHUNK){
                    var state=round(SfcJoinNetwork.State.CODEC,new SfcJoinNetwork.State(7001,epoch,r.token(),r.frame(),bytes.length,off,sha,
                            Arrays.copyOfRange(bytes,off,Math.min(off+SfcJoinGate.CHUNK,bytes.length))));
                    check(state.offset()==received.size(),"snapshot chunk order");received.writeBytes(state.data());
                }
                check(SfcJoinGate.sha(received.toByteArray()).equals(sha),"received snapshot digest");
                playback.restore(r,received.toByteArray(),sha);
            }
            case "commit" -> {
                UUID nonce=token(c);SfcJoinGate gate=gates.get(nonce);
                var applied=round(SfcJoinNetwork.Applied.CODEC,new SfcJoinNetwork.Applied(7001,epoch,nonce,number(c,"frame"),c.get("sha").getAsString(),true));
                check(!gate.commit(HOST,nonce,applied.frame(),applied.sha(),104),"host cannot impersonate candidate acknowledgment");
                check(gate.commit(GUEST,nonce,applied.frame(),applied.sha(),104),"actual candidate snapshot acknowledgment commits");
                check(gate.phase()==SfcJoinGate.Phase.COMMITTED,"committed consent gate");
            }
            case "cancel" -> {
                UUID nonce=token(c);cancelled.add(nonce);playback.cancelJoin(nonce);
                SfcJoinGate gate=gates.get(nonce);if(gate!=null){gate.close();check(!gate.live(104),"cancel closed gate");}
            }
            case "input_edges" -> timeline();
            case "gate_timeout" -> timeout();
            case "old_epoch" -> {
                check(!playback.matches(7001,epoch-1),"reset rejects old epoch at actual client worker gate");
                check(playback.matches(7001,epoch),"reset accepts current epoch");
                check(!playback.matches(7002,epoch),"different runtime cannot reuse epoch");
            }
            case "poll" -> {}
            case "close" -> closePlayback();
            case "quit" -> {closePlayback();quit=true;}
            default -> throw new IllegalArgumentException("Unknown test command");
        }
    }
    private void closePlayback()throws InterruptedException{
        SfcPlayback old=playback;if(old==null)return;
        var leave=round(SfcJoinNetwork.ControllerLeave.CODEC,new SfcJoinNetwork.ControllerLeave(lease,new SfcHomeNetwork.Leave(7001,epoch)));
        check(leave.lease().equals(lease)&&leave.leave().epoch()==epoch,"leave retains exact controller lease");
        current=null;old.close();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(SfcCoreLease.occupied()&&System.nanoTime()<until)Thread.sleep(5);
        check(!SfcCoreLease.occupied(),"worker releases real core lease");playback=null;drain();
        emit("closed",Map.of("observed",observed,"backups",backups,"backup_sha",backupSha,"error",old.error()==null?"":old.error()));
    }
    private void timeline(){
        SfcInputTimeline timeline=new SfcInputTimeline();int seq=0;int[] expected={1,0,256,0,2,0,512,0};
        // Every press/release arrives before a single emulation frame is consumed.
        for(int mask:expected){
            var input=round(SfcJoinNetwork.ControllerInput.CODEC,new SfcJoinNetwork.ControllerInput(lease,new SfcHomeNetwork.Input(7001,epoch,seq++,mask,false)));
            check(input.lease().equals(lease),"input lease survives codec");
            check(timeline.offer(input.input().sequence(),input.input().buttonMask(),input.input().forceRelease()),"same-tick input edge admitted");
        }
        int[] result=new int[expected.length];for(int i=0;i<result.length;i++){result[i]=timeline.next();check(result[i]==expected[i],"same-frame fast tap order preserved");}
        check(timeline.next()==0,"last release is not stuck");
        check(!timeline.offer(seq-1,1,false),"replayed input rejected");
        check(timeline.offer(seq++,4095,false),"hold admitted");check(timeline.offer(seq++,0,true),"force release admitted");
        check(timeline.next()==0&&timeline.pending()==0,"focus loss clears pending pad inputs");
        emit("timeline",Map.of("masks",result));
    }
    private void timeout(){
        UUID nonce=UUID.randomUUID();SfcJoinGate gate=new SfcJoinGate(nonce,HOST,GUEST,lease,CONSOLE,0);
        check(gate.approve(HOST,nonce,true,1),"timeout consent");check(gate.capture(GUEST,20,2),"timeout capture");
        check(gate.live(601),"snapshot pause remains live before thirty seconds");
        check(!gate.live(602),"snapshot pause expires at thirty seconds");
        check(!gate.append(HOST,nonce,20,1,0,SfcJoinGate.sha(new byte[]{0}),new byte[]{0},602),"late snapshot rejected");
        gate.close();check(gate.phase()==SfcJoinGate.Phase.CLOSED,"expired gate can be cleaned");
    }
    @Override public void execute(Runnable action){callbacks.add(action);}
    @Override public boolean isCurrent(SfcPlayback value){return current==value;}
    @Override public void ready(SfcPlayback value,SfcHomeNetwork.Ready ready){
        var packet=round(SfcJoinNetwork.ControllerReady.CODEC,new SfcJoinNetwork.ControllerReady(value.session.controllerLease(),ready));
        check(packet.lease().equals(value.session.controllerLease()),"ready retains exact lease");
        check(packet.ready().equals(ready),"ready codec preserves bootstrap identity");
        emit("ready",Map.of("fps",ready.targetFps(),"hash",ready.initialStateHash(),"lease",packet.lease().toString(),"port",runtimePort,"execution_host",executionHost,"epoch",epoch));
    }
    @Override public void captured(SfcPlayback value,SfcJoinNetwork.Capture r,byte[] bytes,String sha){
        if(cancelled.contains(r.token())){emit("suppressed",Map.of("token",r.token().toString()));return;}
        SfcJoinGate gate=gates.get(r.token());check(gate!=null,"capture belongs to current consent gate");int chunks=0;
        for(int off=0;off<bytes.length;off+=SfcJoinGate.CHUNK){
            var upload=round(SfcJoinNetwork.Upload.CODEC,new SfcJoinNetwork.Upload(new SfcJoinNetwork.State(7001,epoch,r.token(),r.frame(),bytes.length,off,sha,
                    Arrays.copyOfRange(bytes,off,Math.min(off+SfcJoinGate.CHUNK,bytes.length)))));
            var state=upload.value();check(gate.append(HOST,state.token(),state.frame(),state.total(),state.offset(),state.sha(),state.data(),103),"actual gate accepts decoded snapshot chunk");chunks++;
        }
        check(gate.phase()==SfcJoinGate.Phase.APPLYING,"gate has verified full snapshot");
        emit("snapshot",Map.of("token",r.token().toString(),"frame",r.frame(),"sha",sha,"size",bytes.length,"chunks",chunks,"bytes",Base64.getEncoder().encodeToString(bytes)));
    }
    @Override public void applied(SfcPlayback value,SfcJoinNetwork.Capture r,String sha,boolean success){
        var packet=round(SfcJoinNetwork.Applied.CODEC,new SfcJoinNetwork.Applied(7001,epoch,r.token(),r.frame(),sha,success));
        emit("applied",Map.of("token",packet.token().toString(),"frame",packet.frame(),"sha",packet.sha(),"success",packet.success()));
    }
    @Override public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){
        public void submit(short[] pcm,int length,float gain){if(length<0||length>pcm.length)throw new AssertionError("PCM bounds");audioCalls++;}
        public void close(){}
    };}
    @Override public void backup(SfcPlayback value,WasmSfcCore core,int frame){backupSha=SfcJoinGate.sha(core.saveState());backups++;}
    @Override public void mediaFrame(SfcPlayback value,int width,int height,int stride,float aspect,byte[] rgba,short[] pcm,int stereoFrames){mediaCalls++;}
    @Override public void observedFrame(int frame,int width,int height,byte[] rgba,short[] pcm,int length){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");String video=HexFormat.of().formatHex(digest.digest(rgba));
            for(int i=0;i<length;i++){digest.update((byte)pcm[i]);digest.update((byte)(pcm[i]>>>8));}
            String audio=HexFormat.of().formatHex(digest.digest());int r=0,g=0,b=0,total=rgba.length/4;
            for(int i=0;i<rgba.length;i+=4){r+=rgba[i]&255;g+=rgba[i+1]&255;b+=rgba[i+2]&255;}
            observed=frame;emit("frame",Map.of("frame",frame,"width",width,"height",height,"video",video,"audio",audio,
                    "pcm_length",length,"rgb",new int[]{r/total,g/total,b/total}));
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
