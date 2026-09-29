package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.wasm.ZapperWasmNesCore;
import cn.piq.fcarcade.session.*;
import java.nio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Actual final worker + core; no Minecraft client, fake core, socket or commercial ROM. */
public final class ZapperSession26Probe {
    private static int assertions,pureTests;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new AssertionError(e);}}
    private static void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+10_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("worker timeout");Thread.sleep(2);}}
    private record Observed(int gun,String frame,String ram,String pcm){}
    private static final class ObservingCore implements NesCore {
        final ZapperWasmNesCore actual=new ZapperWasmNesCore();final CopyOnWriteArrayList<Observed> seen=new CopyOnWriteArrayList<>();volatile boolean closed;int gun=ZapperInput.NEUTRAL;
        public void loadRom(byte[] rom){actual.loadRom(rom);}public void reset(){actual.reset();}public void setControllerState(int p,int m){actual.setControllerState(p,m);}
        public boolean supportsZapper(){return actual.supportsZapper();}public String stateNamespace(){return actual.stateNamespace();}
        public void setZapperState(int x,int y,boolean off,boolean trigger){gun=ZapperInput.pack(x,y,off,trigger);actual.setZapperState(x,y,off,trigger);}
        public void runFrame(){actual.runFrame();byte[] pixels=new byte[RGBA_BYTES],ram=new byte[CPU_RAM_BYTES];actual.copyFrameRgba(pixels);actual.copyCpuRam(ram);seen.add(new Observed(gun,hash(pixels),hash(ram),null));}
        public void copyFrameRgba(byte[] out){actual.copyFrameRgba(out);}public void copyCpuRam(byte[] out){actual.copyCpuRam(out);}
        public int copyAudioSamples(float[] out){int n=actual.copyAudioSamples(out);var bytes=ByteBuffer.allocate(n*4);for(int i=0;i<n;i++)bytes.putInt(Float.floatToRawIntBits(out[i]));int at=seen.size()-1;
            if(at>=0){var old=seen.get(at);seen.set(at,new Observed(old.gun,old.frame,old.ram,hash(bytes.array())));}return n;}
        public byte[] saveTransientState(){return actual.saveTransientState();}public void loadTransientState(byte[] data){actual.loadTransientState(data);}public void close(){try{actual.close();}finally{closed=true;}}
        boolean reached(int count){return seen.size()==count&&seen.get(count-1).pcm!=null;}
    }
    private static List<ClientNesWorker.Input> inputs(List<LockstepInputRun> runs,long start){var result=new ArrayList<ClientNesWorker.Input>();for(var run:runs){start+=run.frames();result.add(new ClientNesWorker.Input(start,run.playerOneMask(),run.playerTwoMask(),run.zapperState()));}return result;}
    private static byte[] snapshot(ClientNesWorker worker)throws Exception{check(worker.requestSnapshot(),"snapshot queued");long end=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<end){for(var e:worker.drain().events()){if(e.kind().equals("error"))throw new AssertionError(e.message());if(e.kind().equals("snapshot")){check(e.frame()==60,"snapshot at authoritative boundary60");return e.bytes();}}Thread.sleep(2);}throw new AssertionError("snapshot timeout");}
    private static void runPure()throws Exception{var t=Class.forName("cn.piq.fcarcade.session.ZapperLockstepTest");var c=t.getDeclaredConstructor();c.setAccessible(true);
        for(var m:t.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.setAccessible(true);try{m.invoke(c.newInstance());}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(m.getName(),e.getCause());}pureTests++;}check(pureTests==10,"all ten pure production tests");}
    public static void main(String[] args)throws Exception{
        var expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(ClientNesWorker.class,NesCore.class,ZapperWasmNesCore.class,NesCoreVariant.class,ZapperInput.class,ZapperInputQueue.class,LockstepState.class,LockstepTimeline.class,LockstepInputRun.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"final origin "+type);
        runPure();var diagnostic=Class.forName("cn.piq.fcarcade.core.wasm.ZapperCoreProbe").getDeclaredMethod("diagnostic",boolean.class);diagnostic.setAccessible(true);byte[] rom=(byte[])diagnostic.invoke(null,true);
        String romSha=hash(rom);var one=new AtomicReference<ObservingCore>();var two=new AtomicReference<ObservingCore>();
        var a=new ClientNesWorker(()->{var core=new ObservingCore();core.loadRom(rom);one.set(core);return core;},true,true,romSha,false);
        var b=new ClientNesWorker(()->{var core=new ObservingCore();core.loadRom(rom);two.set(core);return core;},true,false,romSha,true);
        int snapshotBytes;
        try{
            await(()->a.isReady()&&b.isReady());check(two.get().seen.isEmpty(),"late observer waits for snapshot; no speculative frames");
            var lock=new LockstepState();lock.restart();var timeline=new LockstepTimeline();timeline.reset(1);var owner=UUID.randomUUID();int sequence=0;
            for(int f=1;f<=150;f++){
                if(f%9==1){check(lock.acceptZapper(owner,1,sequence++,ZapperInput.pack(128,120,false,true),false,false),"press accepted");check(lock.acceptZapper(owner,1,sequence++,ZapperInput.pack(128,120,false,false),false,false),"same-tick release accepted");}
                if(f%15==0)check(lock.acceptInput(owner,1,0,f,f%30==0?0:1),"P1 menu input independent");
                timeline.record(lock.advanceFrame());
                if(f==60){check(a.history(0,inputs(timeline.snapshot(),0)),"prefix queued");await(()->one.get().reached(60));}
            }
            byte[] state=snapshot(a);snapshotBytes=state.length;check(snapshotBytes<=2*1024*1024,"gun snapshot within unchanged network budget");
            check(NesCoreVariant.ZAPPER_V1.acceptsStateHeader(state,romSha),"actual core identity accepted by server header");check(!NesCoreVariant.LEGACY.acceptsStateHeader(state,romSha),"gun cannot enter ordinary save namespace");
            check(!NesCoreVariant.ZAPPER_V1.acceptsStateHeader(state,"00".repeat(32)),"wrong ROM rejected before persistence");
            var suffix=inputs(timeline.snapshotAfter(60),60);check(a.history(60,suffix),"P1 continues without restart");
            Thread.sleep(15);b.snapshot(60,state,false);check(b.history(60,suffix),"delayed observer gets snapshot then identical history");
            await(()->one.get().reached(150)&&two.get().reached(90));
            for(int i=0;i<90;i++){var first=one.get().seen.get(i+60);var second=two.get().seen.get(i);check(first.equals(second),"frame/PCM/RAM/trigger identical at "+(i+61));}
            var expanded=new ArrayList<Integer>();for(var run:timeline.snapshot())for(int i=0;i<run.frames();i++)expanded.add(run.zapperState());
            for(int i=0;i<150;i++)check(one.get().seen.get(i).gun==expanded.get(i),"actual worker applies every authoritative sensor edge "+i);
            check(one.get().seen.get(0).gun!=one.get().seen.get(1).gun,"subtick tap survives actual WASM worker");
            check(a.enqueue(new ClientNesWorker.Input(150,255,255,ZapperInput.pack(20,20,false,true))),"stale frame queue accepts mailbox only");Thread.sleep(20);check(one.get().seen.size()==150,"stale target does not run core");
        }finally{a.close();b.close();}
        await(()->one.get().closed&&two.get().closed);check(!a.appliedZapperTrigger()&&!b.appliedZapperTrigger(),"closed worker clears visual trigger");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"pure_tests\":"+pureTests+",\"workers\":2,\"consistent_join_frames\":90,\"p1_total_frames\":150,\"p1_restarts\":0,\"snapshot_bytes\":"+snapshotBytes+",\"actual_wasm\":true,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"real_network_or_server\":false}");
    }
}
