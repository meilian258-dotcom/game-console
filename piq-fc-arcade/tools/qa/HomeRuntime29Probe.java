package cn.piq.fcarcade.client;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.session.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
/** Executes production worker/core and authority classes; coordinator is not a Minecraft world. */
public final class HomeRuntime29Probe {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void coexist(byte[] rom)throws Exception{
        UUID host=UUID.randomUUID(),guest=UUID.randomUUID();Object source=new Object(),remote=new Object();
        var physical=new HomeControllerLedger();var console=new HomeControllerLedger.Console(UUID.randomUUID(),"minecraft:overworld",1,64,2);
        var borrowed=physical.borrow(console,0,host);check(borrowed.session()==0&&!physical.authorized(borrowed.id(),host,console,0,0),"off-state borrowing has no session/input");
        var authority=new HomeRuntimeAuthority<>(host,source,true);var one=new AtomicReference<HomeRuntime28Probe.Core>();var two=new AtomicReference<HomeRuntime28Probe.Core>();var creates=new AtomicInteger();
        var a=new ClientNesWorker(()->{var c=new HomeRuntime28Probe.Core(true);c.loadRom(rom);one.set(c);creates.incrementAndGet();return c;},true,false,HomeRuntime28Probe.hash(rom),false);
        var b=new ClientNesWorker(()->{var c=new HomeRuntime28Probe.Core(true);c.loadRom(rom);two.set(c);return c;},true,false,HomeRuntime28Probe.hash(rom),true);
        try{
            HomeRuntime28Probe.await(()->a.isReady()&&b.isReady());var clock=new LockstepState();clock.restart();var timeline=new LockstepTimeline();timeline.reset(clock.epoch());
            UUID pad=borrowed.id(),gun=UUID.randomUUID(),freshGun=UUID.randomUUID();HomeControllerLedger.Lease guestPad=null;
            for(int frame=1;frame<=150;frame++){
                if(frame==31){check(physical.activate(pad,host,console,77,0),"Host exact idle object activated");check(authority.take(host,source,pad,0),"P1 on gun runtime");check(authority.takeGun(host,source,gun),"same person holds independent P2 gun");check(authority.player(host).port()==0,"primary P1");clock.acceptInput(pad,1,0,1,1);clock.acceptInput(pad,1,0,2,0);clock.acceptZapper(gun,1,10,ZapperInput.pack(128,120,false,true),false,false);}
                if(frame==45){authority.release(host,source,pad,0);physical.revoke(pad);clock.clearController(0);clock.forgetPlayer(pad);check(authority.authorized(host,source,gun,1),"pad return preserves gun lease");check(!clock.acceptZapper(gun,1,9,ZapperInput.NEUTRAL,true,false),"pad return cannot erase gun replay window");}
                if(frame==60){guestPad=physical.borrow(console,0,guest);check(physical.activate(guestPad.id(),guest,console,77,0),"approved guest uses its original object");check(authority.take(guest,remote,guestPad.id(),0),"new player P1 while Host uses gun");clock.acceptInput(guestPad.id(),1,0,20,255);}
                if(frame==75){authority.release(host,source,gun,1);clock.clearZapper();clock.forgetPlayer(gun);check(authority.authorized(guest,remote,guestPad.id(),0),"gun return preserves remote P1");check(!clock.acceptInput(guestPad.id(),1,0,19,0,true,false),"gun return cannot erase P1 replay window");}
                if(frame==90){check(authority.takeGun(host,source,freshGun),"gun rejoin fresh lease without restart");check(!authority.authorized(host,source,gun,1),"old gun lease denied");clock.acceptZapper(freshGun,1,1,ZapperInput.pack(128,120,false,true),false,false);}
                if(frame==120){check(!HomeRuntimeAuthority.controllerInRange(Math.nextUp(36.0)),"six-block overshoot");authority.release(guest,remote,guestPad.id(),0);physical.revoke(guestPad.id());clock.clearController(0);clock.forgetPlayer(guestPad.id());check(authority.host(host,source)&&authority.authorized(host,source,freshGun,1),"distance only releases offending controller");}
                if(frame==135){authority.release(host,source,freshGun,1);clock.clearZapper();clock.forgetPlayer(freshGun);check(authority.running(),"both empty sockets keep appliance running");}
                timeline.record(clock.advanceFrame());
                if(frame==90){check(a.history(0,HomeRuntime28Probe.inputs(timeline.snapshot(),0)),"real prefix accepted");HomeRuntime28Probe.await(()->one.get().reached(90));}
            }
            for(int i=0;i<30;i++)check(one.get().seen.get(i).p1()==0&&one.get().seen.get(i).gun()==ZapperInput.NEUTRAL,"no-control boot "+i);
            check(one.get().seen.get(30).p1()==1&&one.get().seen.get(31).p1()==0,"subtick P1 tap preserved");
            for(int i=44;i<59;i++)check(one.get().seen.get(i).p1()==0&&ZapperInput.trigger(one.get().seen.get(i).gun()),"returning pad leaves gun firing "+i);
            for(int i=74;i<89;i++)check(one.get().seen.get(i).p1()==255&&one.get().seen.get(i).gun()==ZapperInput.NEUTRAL,"returning gun leaves full P1 buttons "+i);
            byte[] snapshot=HomeRuntime28Probe.snapshot(a,90);var suffix=HomeRuntime28Probe.inputs(timeline.snapshotAfter(90),90);
            check(a.history(90,suffix),"Host same core continuation");b.snapshot(90,snapshot,false);check(b.history(90,suffix),"peer same snapshot suffix");
            HomeRuntime28Probe.await(()->one.get().reached(150)&&two.get().reached(60));
            for(int i=0;i<60;i++)check(one.get().seen.get(90+i).equals(two.get().seen.get(i)),"snapshot RGBA/PCM/RAM/input equality "+i);
            for(int i=119;i<134;i++)check(one.get().seen.get(i).p1()==0&&ZapperInput.trigger(one.get().seen.get(i).gun()),"distance release preserves gun frames "+i);
            for(int i=134;i<150;i++)check(one.get().seen.get(i).p1()==0&&one.get().seen.get(i).gun()==ZapperInput.NEUTRAL,"empty devices neutral running frames "+i);
            check(creates.get()==1,"take/return/distance/rejoin never resets or reloads core");check(!cn.piq.retro.input.InputOwnership.occupied(),"background computation owns no input");
            var finalObject=physical.borrow(console,0,host);check(physical.activate(finalObject.id(),host,console,77,0),"powered physical object");physical.idle(finalObject.id());check(!physical.authorized(finalObject.id(),host,console,77,0)&&physical.player(host).id().equals(finalObject.id()),"power off keeps physical object but removes authorization");
            clock.restart();authority.reset();check(!clock.acceptInput(finalObject.id(),1,0,90,255)&&!clock.acceptZapper(freshGun,1,90,ZapperInput.pack(1,1,false,true),false,false),"old epoch from both devices rejected");a.reset();check(a.enqueue(new ClientNesWorker.Input(3,0,0,ZapperInput.NEUTRAL)),"explicit reset neutral prefix");HomeRuntime28Probe.await(()->creates.get()==2&&one.get().reached(3));
        }finally{authority.close();a.close();b.close();}
        HomeRuntime28Probe.await(()->one.get().closed&&two.get().closed);
    }
    public static void main(String[] args)throws Exception{
        var jar=Path.of(args[0]).toRealPath();for(Class<?> c:List.of(HomeRuntimeAuthority.class,HomeControllerLedger.class,ClientNesWorker.class,LockstepState.class,cn.piq.fcarcade.core.wasm.ZapperWasmNesCore.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final origin "+c);
        var factory=Class.forName("cn.piq.fcarcade.core.wasm.HomeGunController29Probe").getDeclaredMethod("diagnostic");factory.setAccessible(true);byte[] rom=(byte[])factory.invoke(null);
        HomeRuntime28Probe.scenario(false,rom);coexist(rom);
        System.out.println("{\"ok\":true,\"assertions\":"+(checks+HomeRuntime28Probe.checks)+",\"gun_coexistence_assertions\":"+checks+",\"variants\":2,\"host_frames_before_reset\":150,\"consistent_peer_frames\":60,\"take_return_restarts\":0,\"actual_wasm\":true,\"production_origin\":\"final-jar-only\",\"production_compiled\":false,\"minecraft_started\":false,\"real_network_or_server\":false}");
    }
}
