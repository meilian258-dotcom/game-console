package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetMediaAssembler;
import cn.piq.fcarcade.server.hosted.*;
import cn.piq.sfcarcade.core.SfcTwoPortInputProbe;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.SfcHomeMod;
import cn.piq.sfchome.net.*;
import cn.piq.sfchome.server.SfcJoinGate;
import cn.piq.sfchome.server.hosted.SfcServerCoreFactory;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Actual home worker/core/codec/receiver without Minecraft, sockets or an audio device. */
public final class SfcHomeHosted39Probe implements SfcPlayback.Host {
    private static final UUID SOURCE=new UUID(11,12),STREAM=new UUID(13,14),RECEIPT=new UUID(15,16);
    private final Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
    private volatile SfcPlayback receiver;
    private volatile boolean ready;
    private volatile int audioCalls,closedAudio;
    private int assertions;
    private Object worker;
    private Class<?> workerClass;
    private final CabinetMediaAssembler assembly=new CabinetMediaAssembler();
    private void check(boolean result,String why){assertions++;if(!result)throw new AssertionError(why);}
    private Object call(String name,Class<?>[] types,Object... args)throws Exception{var m=workerClass.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(worker,args);}
    private <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);T decoded=codec.decode(b);check(b.readableBytes()==0,"exact codec consumption");return decoded;}finally{b.release();}}
    private void drain(){for(Runnable task;(task=callbacks.poll())!=null;)task.run();}
    private SfcHostedNetwork.Stream packet(CabinetMediaPacket p,UUID receipt){return new SfcHostedNetwork.Stream(9001,1,receipt,new CabinetRoomNetwork.Media(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data()));}
    @SuppressWarnings("unchecked") private boolean pump(int red,int green)throws Exception{
        drain();Object error=call("error",new Class<?>[0]);if(error!=null)throw new AssertionError(error);
        var batch=(List<CabinetMediaPacket>)call("poll",new Class<?>[0]);if(batch==null)return false;
        for(var p:batch){
            receiver.offerMedia(round(SfcHostedNetwork.Stream.CODEC,packet(p,RECEIPT)));
            var complete=assembly.accept(p,System.nanoTime());if(complete==null||p.kind()!=0)continue;
            var h=complete.header();int[] pixels=CabinetMediaCodec.decodeVideo(new CabinetMediaCodec.Encoded(h.width(),h.height(),h.aspect(),h.rotation(),complete.bytes()));
            int good=0;for(int pixel:pixels)if(Math.abs((pixel&255)-red)<25&&Math.abs(((pixel>>>8)&255)-green)<25&&((pixel>>>16)&255)<25)good++;
            if(good>pixels.length/2)return true;
        }
        return false;
    }
    private void color(int p1,int p2,int red,int green)throws Exception{
        call("inputs",new Class<?>[]{int.class,int.class},p1,p2);long until=System.nanoTime()+10_000_000_000L;boolean found=false;
        while(System.nanoTime()<until&&!found){found=pump(red,green);if(!found)Thread.sleep(5);}
        check(found,"actual hosted P1/P2 color "+p1+"/"+p2);
    }
    private void quickEdges()throws Exception{
        var coreField=workerClass.getDeclaredField("core");coreField.setAccessible(true);var core=(ServerCoreHandle)coreField.get(worker);
        var lockField=core.getClass().getDeclaredField("lock");lockField.setAccessible(true);
        var inputField=core.getClass().getDeclaredField("inputs");inputField.setAccessible(true);Object inputs=inputField.get(core);
        var sample=inputs.getClass().getDeclaredMethod("sample");sample.setAccessible(true);
        // Controlled owner-lock scheduling: inspect real queue samples before the native thread can consume them.
        synchronized(lockField.get(core)){
            core.clearInput();call("inputs",new Class<?>[]{int.class,int.class},1,2);call("inputs",new Class<?>[]{int.class,int.class},0,0);
            int[] press=(int[])sample.invoke(inputs),release=(int[])sample.invoke(inputs);
            check(press[0]==1&&press[1]==2,"both ports' fast press survives worker bridge on the same sample");
            check(release[0]==0&&release[1]==0,"ordinary zero is ordered after press, not collapsed");
            call("inputs",new Class<?>[]{int.class,int.class},1,4);call("inputs",new Class<?>[]{int.class,int.class},0,0);call("releasePort",new Class<?>[]{int.class},0);
            press=(int[])sample.invoke(inputs);release=(int[])sample.invoke(inputs);
            check(press[0]==0&&press[1]==4&&release[1]==0,"forced P1 release leaves P2 edges intact");core.clearInput();
        }
    }
    private void run(Path scratch)throws Exception{
        Path library=Files.createDirectory(scratch.resolve("roms"));byte[] rom=SfcTwoPortInputProbe.rom();String sha=SfcJoinGate.sha(rom);Files.write(library.resolve("original-homebrew.sfc"),rom,StandardOpenOption.CREATE_NEW);
        ServerCoreRegistry.register(SfcHomeMod.CABINET_BACKEND,new SfcServerCoreFactory());
        var context=new ServerCoreContext(scratch,scratch.resolve("saves"),new UUID(1,2),new UUID(3,4));
        workerClass=Class.forName("cn.piq.sfchome.server.SfcHostedWorker");var ctor=workerClass.getDeclaredConstructor(UUID.class,UUID.class,ServerCoreContext.class,Path.class,String.class,AutoCloseable.class);ctor.setAccessible(true);
        var capacityClosed=new java.util.concurrent.atomic.AtomicBoolean();
        worker=ctor.newInstance(SOURCE,STREAM,context,library,sha,(AutoCloseable)()->capacityClosed.set(true));
        var session=new SfcHomeNetwork.Session(9001,1,ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),new UUID(3,4),new BlockPos(1,64,0),new UUID(4,5),new UUID(5,6),sha,SfcHomeNetwork.CORE_BUILD,-1,RECEIPT,true,2,SOURCE,STREAM);
        session=round(SfcHomeNetwork.Session.CODEC,session);check(session.serverHosted()&&session.executionHost()&&session.port()==-1,"mode and host role roundtrip");
        try(var held=SfcCoreLease.acquire()){
            receiver=new SfcPlayback(session,new byte[0],new SfcStartupProgress(),this);
            check(SfcCoreLease.occupied(),"another local core remains leased; stream creation did not acquire it");
            var image=CabinetMediaCodec.encodeVideo(new cn.piq.retro.api.RetroFrame(1,1,new int[]{0xffff0000},1,0,new short[0]));
            var fake=new CabinetMediaPacket(SOURCE,STREAM,100,0,0,1,1,1,1,0,2,image.data());
            receiver.offerMedia(packet(fake,new UUID(99,99)));
            var stale=packet(fake,RECEIPT);receiver.offerMedia(new SfcHostedNetwork.Stream(9000,1,RECEIPT,stale.media()));receiver.offerMedia(new SfcHostedNetwork.Stream(9001,2,RECEIPT,stale.media()));
            receiver.offerMedia(packet(new CabinetMediaPacket(new UUID(91,92),STREAM,100,0,0,1,1,1,1,0,2,image.data()),RECEIPT));
            receiver.offerMedia(packet(new CabinetMediaPacket(SOURCE,new UUID(93,94),100,0,0,1,1,1,1,0,2,image.data()),RECEIPT));
            Thread.sleep(50);check(!receiver.started(),"foreign recipient/session/epoch/source/stream never reaches video decoder");
            color(0,0,0,0);quickEdges();color(1,0,255,0);color(0,2048,0,255);color(1,2048,255,255);color(0,0,0,0);
            long until=System.nanoTime()+2_000_000_000L;while((!ready||!receiver.started()||audioCalls==0)&&System.nanoTime()<until){pump(0,0);Thread.sleep(5);}
            check(ready&&receiver.started()&&audioCalls>0,"actual receiver ready/video/audio");check(receiver.error()==null,"receiver healthy");
            check((boolean)call("reset",new Class<?>[0]),"supported actual server reset");receiver.resetMedia();color(0,0,0,0);
            until=System.nanoTime()+1_000_000_000L;while(closedAudio==0&&System.nanoTime()<until){pump(0,0);Thread.sleep(5);}check(closedAudio>0,"reset clears receiver PCM resource");
            var reset=round(SfcHostedNetwork.Reset.CODEC,new SfcHostedNetwork.Reset(9001,1,RECEIPT));check(reset.recipient().equals(RECEIPT),"reset exact capability roundtrip");
            receiver.close();receiver=null;check(SfcCoreLease.occupied(),"receiver close does not release other local core");
        }finally{if(receiver!=null)receiver.close();call("close",new Class<?>[0]);}
        long until=System.nanoTime()+10_000_000_000L;while(ServerCoreRegistry.activeCount(SfcHomeMod.CABINET_BACKEND)>0&&System.nanoTime()<until)Thread.sleep(10);
        check(ServerCoreRegistry.activeCount(SfcHomeMod.CABINET_BACKEND)==0,"core truly terminated before slot release");
        until=System.nanoTime()+1_000_000_000L;while(!capacityClosed.get()&&System.nanoTime()<until)Thread.sleep(5);check(capacityClosed.get(),"global capacity callback only after actual termination");
        check(!SfcCoreLease.occupied(),"test owned local lease closed normally");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_home_worker\":true,\"actual_wasm_core\":true,\"actual_receiver\":true,\"p1_p2_effects\":true,\"reset\":true,\"minecraft_started\":false,\"audio_device_opened\":false,\"socket_opened\":false,\"commercial_rom_used\":false}");
    }
    public static void main(String[] args)throws Exception{new SfcHomeHosted39Probe().run(Path.of(args[0]).toRealPath());}
    public void execute(Runnable r){callbacks.add(r);}public boolean isCurrent(SfcPlayback p){return receiver==p;}
    public void ready(SfcPlayback p,SfcHomeNetwork.Ready r){ready=true;}
    public void captured(SfcPlayback p,SfcJoinNetwork.Capture r,byte[] b,String s){throw new AssertionError("stream attempted local snapshot");}
    public void applied(SfcPlayback p,SfcJoinNetwork.Capture r,String s,boolean ok){throw new AssertionError("stream attempted local restore");}
    public void backup(SfcPlayback p,WasmSfcCore c,int f){throw new AssertionError("stream attempted client save");}
    public SfcPlayback.Audio openAudio(){return new SfcPlayback.Audio(){public void submit(short[] pcm,int length,float gain){if(length*2!=pcm.length)throw new AssertionError("stereo unit mismatch");audioCalls++;}public void close(){closedAudio++;}};}
}
