package cn.piq.sfchome.client.cabinet;

import cn.piq.sfcarcade.core.SfcRomImage;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import cn.piq.sfchome.client.SfcCoreLease;
import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.retro.api.RetroEmulator;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Final JAR interfaces, queue and async adapter, feeding an unchanged actual WASM diagnostic core. */
public final class SfcCabinetFinalPortsProbe {
    private static int checks;
    private static void require(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    private static void origin(Class<?> type,Path jar)throws Exception{
        require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar.toRealPath()),type.getName()+" not final jar");
    }
    private static void until(BooleanSupplier ready)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!ready.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(3);
        require(ready.getAsBoolean(),"actual core deadline");
    }
    private static void invalid(Runnable callback){try{callback.run();throw new AssertionError("Invalid operation accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static void queue(){
        var q=new SfcCabinetInputs();require(q.offer(1,256),"initial both");q.nextFrame();
        for(int i=0;i<128;i++)require(q.offer((i&1)+2,512),"full queue accepted");q.releasePort(1);
        for(int i=0;i<128;i++){var value=q.nextFrame();require(value.p1()==(i&1)+2&&value.p2()==0,"P2 queued bits cannot resurrect; host edge survives");}
        require(q.nextFrame().p2()==0,"P2 held cleared");require(q.offer(3,512),"new P2 lease may press old value");require(q.nextFrame().p2()==512,"same old mask received after release");
        q.clear();q.offer(1,256);q.nextFrame();q.offer(2,512);q.offer(4,0);q.offer(8,1024);q.releasePort(0);
        for(int value:new int[]{512,0,1024,1024})require(q.nextFrame().equals(new SfcCabinetInputs.Pair(0,value)),"P1 release preserves P2 taps");
        q.clear();q.offer(1,256);invalid(()->q.releasePort(-1));invalid(()->q.releasePort(2));require(q.nextFrame().equals(new SfcCabinetInputs.Pair(1,256)),"invalid port did not mutate queue");
    }
    public static void main(String[] args)throws Exception{
        Path sfc=Path.of(args[0]),fc=Path.of(args[1]);
        for(Class<?> type:new Class<?>[]{SfcCabinetInputs.class,SfcCabinetSession.class,SfcCoreLease.class,WasmSfcCore.class})origin(type,sfc);
        origin(CabinetEmulator.class,fc);origin(RetroEmulator.class,fc);queue();
        var observed=new java.util.concurrent.atomic.AtomicReference<SfcCabinetActualCoreProbe.Observed>();
        var session=new SfcCabinetSession(SfcRomImage.fromBytes(SfcCabinetActualCoreProbe.fixture(false)),()->{
            var core=new SfcCabinetActualCoreProbe.Observed();observed.set(core);return core;
        });
        var shared=session.asRetro();origin(shared.getClass(),fc);
        try{
            until(shared::isReady);var core=observed.get();require(shared.maxPlayers()==2&&session.maxPlayers()==2,"SFC remains two ports");
            invalid(()->shared.offerInputs(0,0,1,0));invalid(()->shared.offerInputs(0,0,0,1));invalid(()->shared.releasePort(2));
            shared.offerInputs(64,256,0,0);until(()->core.masks.contains(64|(256<<12)));
            shared.releasePort(1);until(()->core.masks.lastIndexOf(64)>core.masks.indexOf(64|(256<<12)));
            Thread.sleep(75);require((core.masks.getLast()>>>12)==0&&(core.masks.getLast()&4095)==64,"real WASM receives P1 held with P2 neutral");
            shared.offerInputs(64,256,0,0);int rejoin=core.masks.size();until(()->core.masks.subList(Math.min(rejoin,core.masks.size()),core.masks.size()).contains(64|(256<<12)));
            shared.releasePort(0);until(()->core.masks.lastIndexOf(256<<12)>core.masks.lastIndexOf(64|(256<<12)));
            Thread.sleep(40);require(core.masks.getLast()==(256<<12),"real WASM receives P2 held with P1 neutral");
            shared.clearInput();until(()->core.masks.getLast()==0);require(shared.error()==null&&shared.isReady(),"independent releases keep worker running");
            require(shared.pollFrame()!=null,"actual frame still publishes");require(SfcCoreLease.occupied(),"core lease held until worker close");
        }finally{shared.close();until(()->!SfcCoreLease.occupied());}
        System.out.println("{\"passed\":true,\"assertions\":"+checks+",\"production_origin\":\"final-jar-only\",\"real_sfc_wasm\":true,\"independent_port_release\":true,\"queued_edges_preserved\":true,\"commercial_roms\":false,\"minecraft_started\":false}");
    }
}
