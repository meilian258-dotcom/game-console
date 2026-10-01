// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real relay/codec/session/rollback/storage callbacks; fake core only, not a JNI claim. */
class JniNetplaySessionTest {
    static class Core implements LibretroRuntime {
        final InfoHolder av=new InfoHolder();long state=7;Thread owner;boolean closed,pendingReset;
        final boolean gun;
        final List<LibretroProcess.Controls> applied=new CopyOnWriteArrayList<>();
        Core(){this(false);}
        Core(boolean gun){this.gun=gun;}
        public Set<Capability> capabilities(){return gun?Set.of(Capability.STATE,Capability.LIGHT_GUN):Set.of(Capability.STATE);}
        private void check(){assertSame(owner,Thread.currentThread());assertFalse(closed);}
        public LibretroProcess.Info load(byte[] bytes){owner=Thread.currentThread();return info();}
        public LibretroProcess.Info info(){return av.info;}
        public String coreVersion(){return "test";}
        public LibretroProcess.Output run(List<LibretroProcess.Controls> frames,int mask){
            check();if(pendingReset){state=7;pendingReset=false;}for(var frame:frames){var p=frame.pads();state=state*31+p[0]*65537L+p[1]+frame.gun()*13L;
                if(p.length>2)state+=p[2]*41L+p[3]*59L;applied.add(frame);}
            byte[] picture=(mask&1)==0?new byte[0]:new byte[256*240*4];
            if(picture.length>0)ByteBuffer.wrap(picture).putLong(state);
            return new LibretroProcess.Output(info(),false,picture,(mask&2)==0?new short[0]:new short[1470],new byte[0]);
        }
        public LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> frames,int mask,int id){return run(frames,mask);}
        public LibretroProcess.Info reset(){check();pendingReset=true;return info();}
        public byte[] serialize(){check();return ByteBuffer.allocate(8).putLong(state).array();}
        public void restore(byte[] bytes){check();if(bytes.length!=8)throw new IllegalArgumentException();state=ByteBuffer.wrap(bytes).getLong();}
        public byte[] memory(int id){check();return new byte[0];}
        public LibretroSaveMemory saveMemory(){check();return new LibretroSaveMemory(new byte[0],new byte[0]);}
        public void restoreSaveMemory(LibretroSaveMemory memory){check();}
        public byte[] persistenceIdentity(){return new byte[32];}
        public void close(){check();closed=true;}
    }
    static class InfoHolder {final LibretroProcess.Info info=new LibretroProcess.Info(256,240,256,240,4f/3,60,44100,1);}
    static final byte[] ROM=new byte[16];
    static class Room implements AutoCloseable {
        final Object hostKey=new Object();
        final Map<Object,JniNetplaySession> runs=new ConcurrentHashMap<>();
        final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
        final NetplayRelay<Object> relay;
        final JniNetplaySession host;
        final AtomicReference<byte[]> saved=new AtomicReference<>();
        final AtomicInteger commits=new AtomicInteger();
        final boolean gun;
        final Core hostCore;
        volatile int delay=0;
        volatile JniNetplaySession bufferedTarget;
        final Queue<Runnable> buffered=new ConcurrentLinkedQueue<>();
        Room(boolean persist){
            this(persist,false);
        }
        Room(boolean persist,boolean gun){
            this.gun=gun;hostCore=new Core(gun);
            relay=new NetplayRelay<>(700,hostKey,(target,message)->{
                var run=runs.get(target);if(run!=null){Runnable delivery=()->run.receive(message.chunk(),message.port());
                    synchronized(buffered){if(run==bufferedTarget)buffered.add(delivery);else wire.schedule(delivery,delay,TimeUnit.MILLISECONDS);}}
            });
            var ticket=relay.grant(hostKey,0);
            host=new JniNetplaySession(new NetplayProcess.Grant(700,ticket.id(),true,true,0),()->ROM,
                    packet->relay.receive(hostKey,packet),gun,()->hostCore);
            if(persist)host.persistence(new NetplayProcess.Persistence(){
                public byte[] load(NetplaySaveState.Identity identity){return saved.get();}
                public CompletableFuture<Void> save(byte[] bytes){NetplaySaveState.decode(bytes,FcNetplaySaves.jniIdentity(gun,NetplaySaveState.hash(ROM)));saved.set(bytes);commits.incrementAndGet();return CompletableFuture.completedFuture(null);}
            });
            runs.put(hostKey,host);
            wire.scheduleAtFixedRate(()->relay.renew(Set.copyOf(runs.keySet())),0,100,TimeUnit.MILLISECONDS);
            host.start();
        }
        JniNetplaySession join(int port){
            Object key=new Object();var ticket=relay.grant(key,port);
            var run=new JniNetplaySession(new NetplayProcess.Grant(700,ticket.id(),false,port>=0,port),()->ROM,
                    packet->wire.schedule(()->relay.receive(key,packet),delay,TimeUnit.MILLISECONDS),gun,()->new Core(gun));
            runs.put(key,run);run.start();return run;
        }
        void disconnect(JniNetplaySession run){
            Object key=runs.entrySet().stream().filter(e->e.getValue()==run).findFirst().orElseThrow().getKey();
            relay.revoke(key);runs.remove(key);run.close();
        }
        void healthy(){for(var r:runs.values())assertNull(r.error(),r.diagnostic());}
        public void close()throws Exception{
            for(var r:runs.values())r.close();
            for(var r:runs.values())r.terminated().get(10,TimeUnit.SECONDS);
            relay.close();wire.shutdownNow();assertTrue(wire.awaitTermination(3,TimeUnit.SECONDS));
        }
    }
    static void until(java.util.function.BooleanSupplier check,Room room)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!check.getAsBoolean()&&System.nanoTime()<end){room.healthy();Thread.sleep(5);}
        room.healthy();assertTrue(check.getAsBoolean(),"timed out: "+room.runs.values().stream().map(JniNetplaySession::diagnostic).toList()+" relay="+room.relay.lastRejection());
    }
    @Test void twoPlayersObserverDelayedInputDisconnectRejoinAndHostSave()throws Exception{
        try(var room=new Room(true)){
            until(room.host::ready,room);room.delay=35;
            var peer=room.join(1);until(peer::ready,room);
            peer.input(256);room.host.input(1);
            until(()->room.host.framesReceived()>90&&peer.framesReceived()>80,room);
            long before=room.host.framesReceived();peer.input(16);
            until(()->room.host.framesReceived()>before+12,room);
            assertTrue(room.host.replayedFrames()>0,"changed delayed 2P input must cause a real replay");
            var observer=room.join(-1);until(observer::ready,room);
            until(()->observer.framesReceived()>65,room); // Crosses a real confirmed-state digest exchange.
            peer.input(0);room.host.input(0);
            room.host.saveNow().get(10,TimeUnit.SECONDS);assertEquals(1,room.commits.get());
            assertNotNull(room.saved.get());assertTrue(room.host.diagnostic().contains("重演帧"));
            room.disconnect(peer);peer.terminated().get(3,TimeUnit.SECONDS);
            var rejoin=room.join(1);until(rejoin::ready,room);rejoin.input(8);
            until(()->rejoin.framesReceived()>65,room);
            assertFalse(rejoin.canSave());assertFalse(observer.canSave());
            assertThrows(ExecutionException.class,()->observer.saveNow().get());
            assertThrows(ExecutionException.class,()->peer.checkpoint().get());
            room.healthy();room.host.close();room.host.terminated().get(10,TimeUnit.SECONDS);
            assertTrue(room.commits.get()>=2,"final save must have committed");
        }
    }
    @Test void jniSaveIdentityCannotReadOrOverwriteRetroarchSave() {
        String rom=NetplaySaveState.hash(ROM);
        assertNotEquals(FcNetplaySaves.identity(false,rom),FcNetplaySaves.jniIdentity(rom));
        var bytes=NetplaySaveState.encode(new NetplaySaveState.Parts(FcNetplaySaves.jniIdentity(rom),8,new byte[]{1},new byte[0],new byte[0]));
        assertFalse(FcNetplaySaves.accepts(false,rom,bytes));assertTrue(FcNetplaySaves.accepts(false,true,rom,bytes));
        assertNotEquals(FcNetplaySaves.key(false,"card|1"),FcNetplaySaves.key(false,true,"card|1"));
        assertNotEquals(FcNetplaySaves.key(false,true,"card|1"),FcNetplaySaves.key(true,true,"card|1"));
        assertNotEquals(FcNetplaySaves.jniIdentity(rom),FcNetplaySaves.jniIdentity(true,rom));
        assertFalse(FcNetplaySaves.accepts(true,true,rom,bytes));
    }
    @Test void r2CannotReadOrOverwriteThePreviousJniTrialSave() {
        var p=JniNetplaySession.profile();
        var descriptor="PIQ-JNI-Netplay-v1\n81989a6d9932c928a9a75b63ae7100381b99529ddba3064d0d92d66c2162aabe\n"
                +p.name()+"\n"+p.extension()+"\n"+p.fullPath()+"\n"+p.devices()+"\n"+p.options();
        String rom=NetplaySaveState.hash(ROM);
        var old=new NetplaySaveState.Identity(NetplaySaveState.hash(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                FcNetplaySaves.identity(false,rom).content());
        var bytes=NetplaySaveState.encode(new NetplaySaveState.Parts(old,8,new byte[]{1},new byte[0],new byte[0]));
        assertFalse(FcNetplaySaves.accepts(false,true,rom,bytes));
        assertEquals("core|nes-jni-netplay-v2|card|1",FcNetplaySaves.key(false,true,"card|1"));
        assertNotEquals("core|nes-jni-netplay-v1|card|1",FcNetplaySaves.key(false,true,"card|1"));
    }
    @Test void inexactCoreStillFailsClosedAndCannotSave()throws Exception {
        Core broken=new Core(){@Override public void restore(byte[] bytes){super.restore(bytes);state++;}};
        var run=new JniNetplaySession(new NetplayProcess.Grant(701,UUID.randomUUID(),true,true,0),()->ROM,
                packet->{},()->broken);
        AtomicInteger commits=new AtomicInteger();
        run.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){return null;}
            public CompletableFuture<Void> save(byte[] bytes){commits.incrementAndGet();return CompletableFuture.completedFuture(null);}
        });
        run.start();run.terminated().get(10,TimeUnit.SECONDS);
        assertNotNull(run.error());assertFalse(run.ready());assertTrue(broken.closed);
        assertEquals(0,commits.get());
    }
    @Test void invalidSpectatorCannotStopTheHost()throws Exception {
        try(var room=new Room(false)) {
            until(room.host::ready,room);
            var ticket=UUID.randomUUID();
            room.host.receive(new NetplayChunk(700,ticket,NetplayChunk.OPEN,0,new byte[0]),-1);
            room.host.receive(new NetplayChunk(700,ticket,NetplayChunk.DATA,0,new byte[]{1,2,3}),-1);
            long before=room.host.framesReceived();
            until(()->room.host.framesReceived()>before+5,room);
            assertTrue(room.host.diagnostic().contains("拒绝异常连接：1"));
            assertTrue(room.host.ready());
        }
    }
    @Test void gunUsesOnlyServerMailboxAndSpectatorsReceiveCanonicalFramesAndSaves()throws Exception {
        try(var room=new Room(true,true)) {
            until(room.host::ready,room);room.delay=35;
            var spectator=room.join(-1);until(spectator::ready,room);
            assertThrows(IllegalArgumentException.class,()->new JniNetplaySession(
                    new NetplayProcess.Grant(700,UUID.randomUUID(),false,true,1),()->ROM,p->{},true,()->new Core(true)));
            int shot=cn.piq.fcarcade.session.ZapperInput.pack(211,47,false,true);
            int release=cn.piq.fcarcade.session.ZapperInput.pack(211,47,false,false);
            room.host.input(65535);spectator.input(65535);
            spectator.authoritativeGun(1,1,255,shot); // A non-host cannot inject authoritative input.
            long before=room.host.framesReceived();
            until(()->room.host.framesReceived()>before+3,room);
            assertTrue(room.hostCore.applied.stream().allMatch(v->v.pads()[0]==0&&v.pads()[1]==0&&v.gun()==65536));
            room.host.authoritativeGun(1,1,1,shot);
            room.host.authoritativeGun(1,2,0,release);
            until(()->room.hostCore.applied.stream().anyMatch(v->v.gun()==shot),room);
            until(()->room.hostCore.applied.stream().anyMatch(v->v.gun()==release),room);
            // Motion may coalesce within one trigger state; test offscreen after consuming release.
            room.host.authoritativeGun(1,3,0,65536);
            assertTrue(room.hostCore.applied.stream().anyMatch(v->v.gun()==shot&&v.pads()[0]==256));
            room.host.authoritativeGun(1,2,255,shot); // Replayed server sequence is ignored.
            room.host.authoritativeGun(2,4,0,shot);
            until(()->room.host.framesReceived()>before+65&&spectator.framesReceived()>65,room);
            assertEquals(65536,room.hostCore.applied.getLast().gun(),"lost lease/heartbeat must neutralize the gun");
            var late=room.join(-1);until(late::ready,room);until(()->late.framesReceived()>65,room);
            room.host.saveNow().get(10,TimeUnit.SECONDS);
            assertTrue(FcNetplaySaves.accepts(true,true,NetplaySaveState.hash(ROM),room.saved.get()));
            assertFalse(FcNetplaySaves.accepts(false,true,NetplaySaveState.hash(ROM),room.saved.get()));
            room.healthy();
        }
    }
    @Test void orderedBurstAfterSpectatorDelayCatchesUpWithoutWeakeningTimelineChecks()throws Exception {
        try(var room=new Room(false,true)) {
            until(room.host::ready,room);
            var peer=room.join(-1);until(peer::ready,room);
            until(()->peer.framesReceived()>70,room);
            room.bufferedTarget=peer;long before=room.host.framesReceived();
            until(()->room.host.framesReceived()>before+75,room);
            assertTrue(room.buffered.size()>32,"exercise a burst beyond the canonical window");
            // Keep collecting while the exact queued deliveries are released in order.
            room.wire.submit(()->{synchronized(room.buffered){Runnable r;while((r=room.buffered.poll())!=null)r.run();room.bufferedTarget=null;}}).get(3,TimeUnit.SECONDS);
            until(()->peer.framesReceived()>before+75,room);
            until(()->room.host.framesReceived()-peer.framesReceived()<12,room);
            assertTrue(room.host.diagnostic().contains("拒绝异常连接：0"));
        }
    }
    @Test void fourPortCabinetUsesHostAuthorizedInputsAndConsumesPaidCoinsOnlyOnce()throws Exception {
        var runtime=new LibretroProfile("Test","zip",true,List.of(1,1,1,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact("/core/test.dll","a".repeat(64))));
        var profile=new NetplayProfile(getClass(),"/core/test.dll","a".repeat(64),"test.zip",Map.of(),1,48000,1024,4).withJni(runtime);
        var inputs=new NetplayCabinetInputs(true);var factory=new AtomicReference<Core>();
        var peers=new ConcurrentHashMap<Object,JniNetplaySession>();Object hostKey=new Object();
        var relay=new NetplayRelay<Object>(750,hostKey,(target,p)->{var r=peers.get(target);if(r!=null)r.receive(p.chunk(),p.port());},4);
        var keys=new ArrayList<Object>();keys.add(hostKey);keys.add(new Object());keys.add(new Object());
        try {
            for(int i=0;i<3;i++){
                final int index=i,port=i==0?0:i+1;Object key=keys.get(i);var ticket=relay.grant(key,port);
                var r=new JniNetplaySession(new NetplayProcess.Grant(750,ticket.id(),i==0,true,port),()->ROM,p->relay.receive(key,p),false,()->{
                    var core=new Core(){@Override public LibretroProcess.Info loadBundle(String name,Map<String,byte[]> files){assertEquals(Set.of("test.zip","bios.zip"),files.keySet());return load(files.get(name));}};
                    if(index==0)factory.set(core);return core;
                },profile,()->Map.of("bios.zip",new byte[]{1}),i==0?inputs:new NetplayCabinetInputs(true));
                peers.put(key,r);r.start();
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(peers.values().stream().anyMatch(r->r.framesReceived()<90)&&System.nanoTime()<end){
                relay.renew(Set.copyOf(peers.keySet()));for(var r:peers.values()){assertNull(r.error(),r.diagnostic());r.input(65535);}
                inputs.input(2,256|4,System.nanoTime());inputs.input(3,512|4,System.nanoTime());Thread.sleep(5);
            }
            assertTrue(peers.values().stream().allMatch(r->r.framesReceived()>=90));
            var core=factory.get();assertTrue(core.applied.stream().allMatch(f->Arrays.stream(f.pads()).allMatch(p->(p&4)==0)),"raw paid coin is not accepted");
            assertTrue(core.applied.stream().anyMatch(f->f.pads()[2]==256&&f.pads()[3]==512));
            assertTrue(inputs.coin(3,1));assertFalse(inputs.coin(3,1));inputs.release(3);
            long before=peers.get(hostKey).framesReceived();
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(peers.get(hostKey).framesReceived()<before+15&&System.nanoTime()<end){relay.renew(Set.copyOf(peers.keySet()));for(var r:peers.values())assertNull(r.error(),r.diagnostic());Thread.sleep(5);}
            assertTrue(peers.get(hostKey).framesReceived()>=before+15,"cabinet stopped advancing");
            assertEquals(3,core.applied.stream().filter(f->(f.pads()[3]&4)!=0).count(),"one authorized coin, exactly three down frames");
            assertTrue(core.applied.stream().allMatch(f->f.pads()[0]==0&&f.pads()[1]==0),"local peer API cannot bypass cabinet authorization");
        } finally {
            peers.values().forEach(JniNetplaySession::close);for(var r:peers.values())r.terminated().get(10,TimeUnit.SECONDS);relay.close();
        }
    }
}
