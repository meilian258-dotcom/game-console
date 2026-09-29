package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.wasm.*;
import cn.piq.fcarcade.home.HomeRuntimeAuthority;
import cn.piq.fcarcade.session.*;
import java.nio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Real worker and both real WASM modules; no Minecraft, socket or commercial ROM. */
public final class HomeRuntime28Probe {
    static int checks,pure;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new AssertionError(e);}}
    static void await(BooleanSupplier ready)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(!ready.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("worker deadline");Thread.sleep(2);}}
    record Observation(int p1,int p2,int gun,String frame,String ram,String pcm){}
    static final class Core implements NesCore {
        final NesCore actual;final List<Observation> seen=new CopyOnWriteArrayList<>();volatile boolean closed;int p1,p2,gun=ZapperInput.NEUTRAL;
        Core(boolean lightgun){actual=lightgun?new ZapperWasmNesCore():new WasmNesCore();}
        public void loadRom(byte[] r){actual.loadRom(r);}public void reset(){actual.reset();}
        public void setControllerState(int p,int m){if(p==0)p1=m;else p2=m;actual.setControllerState(p,m);}
        public boolean supportsZapper(){return actual.supportsZapper();}public String stateNamespace(){return actual.stateNamespace();}
        public void setZapperState(int x,int y,boolean off,boolean fire){gun=ZapperInput.pack(x,y,off,fire);actual.setZapperState(x,y,off,fire);}
        public void runFrame(){actual.runFrame();byte[] pixels=new byte[RGBA_BYTES],ram=new byte[CPU_RAM_BYTES];actual.copyFrameRgba(pixels);actual.copyCpuRam(ram);seen.add(new Observation(p1,p2,gun,hash(pixels),hash(ram),null));}
        public void copyFrameRgba(byte[] b){actual.copyFrameRgba(b);}public void copyCpuRam(byte[] b){actual.copyCpuRam(b);}
        public int copyAudioSamples(float[] out){int n=actual.copyAudioSamples(out);ByteBuffer bytes=ByteBuffer.allocate(n*4);for(int i=0;i<n;i++)bytes.putInt(Float.floatToRawIntBits(out[i]));int i=seen.size()-1;if(i>=0){var x=seen.get(i);seen.set(i,new Observation(x.p1,x.p2,x.gun,x.frame,x.ram,hash(bytes.array())));}return n;}
        public byte[] saveTransientState(){return actual.saveTransientState();}public void loadTransientState(byte[] b){actual.loadTransientState(b);}
        public void close(){try{actual.close();}finally{closed=true;}}
        boolean reached(int count){return seen.size()==count&&seen.get(count-1).pcm()!=null;}
    }
    static byte[] snapshot(ClientNesWorker worker,long expected)throws Exception{
        check(worker.requestSnapshot(),"background snapshot queued");long until=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<until){for(var e:worker.drain().events()){if(e.kind().equals("error"))throw new AssertionError(e.message());if(e.kind().equals("snapshot")){check(e.frame()==expected,"snapshot exact frame");return e.bytes();}}Thread.sleep(2);}throw new AssertionError("snapshot missing");
    }
    static List<ClientNesWorker.Input> inputs(List<LockstepInputRun> runs,long start){var out=new ArrayList<ClientNesWorker.Input>();for(var r:runs){start+=r.frames();out.add(new ClientNesWorker.Input(start,r.playerOneMask(),r.playerTwoMask(),r.zapperState()));}return out;}
    static void scenario(boolean gun,byte[] rom)throws Exception{
        UUID host=UUID.randomUUID(),guest=UUID.randomUUID();Object source=new Object(),other=new Object();var authority=new HomeRuntimeAuthority<>(host,source);
        var first=new AtomicReference<Core>();var second=new AtomicReference<Core>();var creates=new AtomicInteger();
        var a=new ClientNesWorker(()->{var c=new Core(gun);c.loadRom(rom);first.set(c);creates.incrementAndGet();return c;},true,false,hash(rom),false);
        var b=new ClientNesWorker(()->{var c=new Core(gun);c.loadRom(rom);second.set(c);return c;},true,false,hash(rom),true);
        try{
            await(()->a.isReady()&&b.isReady());check(authority.player(host)==null,"power creates no fake P1");check(!cn.piq.retro.input.InputOwnership.occupied(),"background worker owns no input");
            var clock=new LockstepState();clock.restart();var timeline=new LockstepTimeline();timeline.reset(clock.epoch());UUID one=UUID.randomUUID(),two=UUID.randomUUID();
            for(int frame=1;frame<=150;frame++){
                if(frame==31){check(authority.take(host,source,one,0),gun?"explicit take gun authority":"explicit take P1");if(!gun){check(authority.take(guest,other,two,1),"explicit take P2");clock.acceptInput(guest,1,1,1,128);}clock.acceptInput(host,1,0,1,1);clock.acceptInput(host,1,0,2,0);if(gun)clock.acceptZapper(host,1,1,ZapperInput.pack(128,120,false,true),false,false);}
                if(frame==61){var removed=authority.release(host,source);clock.clearController(removed.port());clock.forgetPlayer(host);clock.clearZapper();check(authority.host(host,source),"P1 return preserves computation host");}
                if(frame==121){if(!gun){var removed=authority.release(guest,other);clock.clearController(removed.port());clock.forgetPlayer(guest);}check(authority.running(),"last controller/gun return does not stop appliance");}
                timeline.record(clock.advanceFrame());
                if(frame==90){check(a.history(0,inputs(timeline.snapshot(),0)),"background prefix queued");await(()->first.get().reached(90));}
            }
            for(int i=0;i<30;i++){check(first.get().seen.get(i).p1()==0&&first.get().seen.get(i).p2()==0,"no-controller boot neutral");}
            check(first.get().seen.get(30).p1()==1&&first.get().seen.get(31).p1()==0,"subtick press/release preserved");
            for(int i=60;i<90;i++)check(first.get().seen.get(i).p1()==0&&first.get().seen.get(i).p2()==(gun?0:128),gun?"gun return leaves both controller sockets neutral":"returned P1 leaves P2 untouched");
            byte[] state=snapshot(a,90);var suffix=inputs(timeline.snapshotAfter(90),90);
            check(a.history(90,suffix),"host continues same core");b.snapshot(90,state,false);check(b.history(90,suffix),"spectator follows stored snapshot");
            await(()->first.get().reached(150)&&second.get().reached(60));
            for(int i=0;i<60;i++)check(first.get().seen.get(i+90).equals(second.get().seen.get(i)),"actual RGB PCM RAM/inputs equal "+i);
            for(int i=120;i<150;i++)check(first.get().seen.get(i).p1()==0&&first.get().seen.get(i).p2()==0&&first.get().seen.get(i).gun()==ZapperInput.NEUTRAL,"empty sockets keep neutral frames");
            check(creates.get()==1,"taking/returning either controller does not restart core");check(!cn.piq.retro.input.InputOwnership.occupied(),"background snapshot/observer never own input");
            int oldEpoch=clock.epoch();clock.restart();authority.reset();a.reset();check(!clock.acceptInput(host,oldEpoch,0,100,255),"old epoch rejected after reset");
            check(a.enqueue(new ClientNesWorker.Input(3,0,0,ZapperInput.NEUTRAL)),"new epoch neutral reset prefix");await(()->creates.get()==2&&first.get().reached(3));
            check(a.appliedControllerMask(0)==0&&a.appliedControllerMask(1)==0&&!a.appliedZapperTrigger(),"reset has no old key or trigger");
        }finally{authority.close();a.close();b.close();}
        await(()->first.get().closed&&second.get().closed);check(!authority.running(),"explicit power off closes authority");
    }
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();for(Class<?> type:List.of(HomeRuntimeAuthority.class,SessionRoster.class,LockstepState.class,ClientArcadeSession.class,ClientNesWorker.class,WasmNesCore.class,ZapperWasmNesCore.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"final source "+type);
        var tests=Class.forName("cn.piq.fcarcade.home.HomeRuntimeAuthorityTest");var ctor=tests.getDeclaredConstructor();ctor.setAccessible(true);for(var m:tests.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.setAccessible(true);try{m.invoke(ctor.newInstance());}catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(m.getName(),e.getCause());}pure++;}
        var method=Class.forName("cn.piq.fcarcade.core.wasm.ZapperCoreProbe").getDeclaredMethod("diagnostic",boolean.class);method.setAccessible(true);byte[] rom=(byte[])method.invoke(null,true);scenario(false,rom);scenario(true,rom);
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"pure_tests\":"+pure+",\"variants\":2,\"frames_per_host_before_reset\":150,\"consistent_observer_frames_per_variant\":60,\"take_return_restarts\":0,\"explicit_reset_restarts_per_variant\":1,\"actual_wasm\":true,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"real_network_or_server\":false}");
    }
}
