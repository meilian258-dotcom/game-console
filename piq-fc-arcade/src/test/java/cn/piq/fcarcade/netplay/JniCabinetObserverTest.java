package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual session/relay with a fake core: tests topology and authority, not a live Minecraft claim. */
class JniCabinetObserverTest {
    static final byte[] ROM=new byte[16];
    static NetplayProfile profile(){
        var runtime=new LibretroProfile("Test","zip",true,List.of(1,1,1,1),false,Map.of(),
                Map.of("windows-x64",new LibretroProfile.Artifact("/core/test.dll","a".repeat(64))));
        return new NetplayProfile(JniCabinetObserverTest.class,"/core/test.dll","a".repeat(64),"test.zip",Map.of(),1,48000,1024,4)
                .withJni(runtime).withJniAspect(NetplayProfile.JniAspect.PRESENTED);
    }
    static class Core extends JniNetplaySessionTest.Core {
        @Override public LibretroProcess.Info info(){return new LibretroProcess.Info(256,240,256,240,3f/4,60,48000,1);}
        @Override public int rotation(){return 1;}
        @Override public LibretroProcess.Info loadBundle(String name,Map<String,byte[]> files){return load(files.get(name));}
    }
    static class Room implements AutoCloseable {
        final Object hostKey=new Object();
        final Map<Object,JniNetplaySession> runs=new ConcurrentHashMap<>();
        final Map<JniNetplaySession,Core> cores=new ConcurrentHashMap<>();
        final NetplayCabinetInputs inputs=new NetplayCabinetInputs(true);
        final NetplayRelay<Object> relay=new NetplayRelay<>(804,hostKey,(key,p)->{
            var r=runs.get(key);if(r!=null)r.receive(p.chunk(),p.port());
        },4);
        final JniNetplaySession host=create(hostKey,0,inputs);
        Room(){host.start();}
        JniNetplaySession create(Object key,int port,NetplayCabinetInputs mailbox){
            var ticket=relay.grant(key,port);var core=new Core();
            var run=new JniNetplaySession(new NetplayProcess.Grant(804,ticket.id(),key==hostKey,port>=0,port),()->ROM,
                    p->relay.receive(key,p),false,()->core,profile(),Map::of,mailbox);
            runs.put(key,run);cores.put(run,core);return run;
        }
        JniNetplaySession observer(){var r=create(new Object(),-1,new NetplayCabinetInputs(true));r.start();return r;}
        void await(BooleanSupplier done)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!done.getAsBoolean()&&System.nanoTime()<end){
                for(var r:runs.values())assertNull(r.error(),r.diagnostic());
                relay.renew(Set.copyOf(runs.keySet()));
                inputs.input(2,256,System.nanoTime());inputs.input(3,512,System.nanoTime());Thread.sleep(5);
            }
            for(var r:runs.values())assertNull(r.error(),r.diagnostic());
            assertTrue(done.getAsBoolean(),"timed out: "+runs.values().stream().map(JniNetplaySession::diagnostic).toList());
        }
        void disconnect(JniNetplaySession r)throws Exception{
            Object key=runs.entrySet().stream().filter(e->e.getValue()==r).findFirst().orElseThrow().getKey();
            relay.revoke(key);runs.remove(key);r.close();r.terminated().get(10,TimeUnit.SECONDS);
        }
        public void close()throws Exception{
            for(var r:runs.values())r.close();
            for(var r:runs.values())r.terminated().get(10,TimeUnit.SECONDS);
            relay.close();
        }
    }
    @Test void observerConstructionUsesFourPortTimelineWithoutCreatingAnInputSeat(){
        var grant=new NetplayProcess.Grant(804,UUID.randomUUID(),false,false);
        assertThrows(IllegalArgumentException.class,()->new NetplayProcess(grant,()->ROM,p->{},profile(),Map::of));
        try(var process=new NetplayProcess(grant,()->ROM,p->{},profile(),Map::of,true)){
            assertEquals(-1,process.grant().port());assertFalse(process.grant().host());assertFalse(process.grant().player());
            assertFalse(process.ready()); // Construction does not launch a core or claim a seat.
        }
    }
    @Test void lateFourPortObserverSeesP3P4AndCanRejoinButCannotInputOrSave()throws Exception{
        try(var room=new Room()){
            room.await(()->room.host.framesReceived()>75);
            var observer=room.observer();room.await(observer::ready);observer.input(65535);
            room.await(()->observer.framesReceived()>75);
            var core=room.cores.get(observer);
            assertTrue(core.applied.stream().anyMatch(f->f.pads()[2]==256&&f.pads()[3]==512));
            assertTrue(core.applied.stream().allMatch(f->f.pads()[0]==0&&f.pads()[1]==0));
            var picture=observer.poll();assertNotNull(picture);
            assertEquals(1,picture.rotation());assertEquals(4f/3,picture.aspect(),1e-6f);
            assertEquals(3.0/4,cn.piq.fcarcade.layout.CabinetVideoGeometry.displayAspect(picture.aspect(),picture.rotation()),1e-6);
            assertFalse(observer.canSave());assertThrows(ExecutionException.class,()->observer.checkpoint().get());
            assertThrows(ExecutionException.class,()->observer.saveNow().get());
            assertThrows(IllegalStateException.class,()->observer.persistence(new NetplayProcess.Persistence(){
                public byte[] load(NetplaySaveState.Identity identity){return null;}
                public CompletableFuture<Void> save(byte[] bytes){throw new AssertionError("observer cannot commit");}
            }));
            room.disconnect(observer);
            var rejoined=room.observer();room.await(()->rejoined.framesReceived()>75);
            assertTrue(room.cores.get(rejoined).applied.stream().anyMatch(f->f.pads()[2]==256&&f.pads()[3]==512));
            assertFalse(rejoined.canSave());
        }
    }
    @Test void watchPreparationWiresTrustedTopologySeparatelyFromReadOnlyGrant()throws Exception{
        String root="src/main/java/cn/piq/fcarcade/client/";
        var preparation=Files.readString(Path.of(root+"watch/NetplayWatchContent.java"));
        assertTrue(preparation.contains("this(load,cancel,false)"),"home-console compatibility remains two-port");
        var watch=Files.readString(Path.of(root+"watch/WatchClient.java"));
        assertTrue(watch.contains("new NetplayProcess.Grant(grant.wire(),grant.ticket(),false,false)"));
        assertTrue(watch.contains("captured.open(new NetplayProcess.Grant"));
        assertTrue(preparation.contains("content::auxiliary,cabinetTopology"));
        assertTrue(preparation.contains("grant.host()||grant.player()||grant.port()!=-1"),"factory cannot be used to acquire a seat");
        var cabinet=Files.readString(Path.of(root+"cabinet/CabinetClientBackends.java"));
        assertTrue(cabinet.contains("boolean cabinetTopology=PgmServicePolicy.supportsBackend(start.backend().toString())"));
        assertTrue(cabinet.contains("CabinetSharedGames.cancel(request.lease()),cabinetTopology"));
    }
}
