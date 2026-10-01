// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated JVM only. Uses the original MesenGunProbe diagnostic ROM, not a game asset. */
public final class GunNetplayProbe {
    static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);System.out.println("PASS "+text);}
    static class Measured implements LibretroRuntime {
        final LibretroRuntime core=new LibretroJniRuntime(JniNetplaySession.profile(true),NetplayProcess.class);
        volatile int trigger,light;
        public LibretroProcess.Info load(byte[] b){return core.load(b);}
        public LibretroProcess.Info info(){return core.info();}
        public String coreVersion(){return core.coreVersion();}
        public Set<Capability> capabilities(){return core.capabilities();}
        public int rotation(){return core.rotation();}
        public LibretroProcess.Output run(List<LibretroProcess.Controls> input,int mask){
            var output=core.run(input,mask);byte[] ram=core.memory(2);
            trigger=ram[0x30]&16;light=ram[0x31]&255;return output;
        }
        public LibretroProcess.Output runWithMemory(List<LibretroProcess.Controls> input,int mask,int region){return core.runWithMemory(input,mask,region);}
        public LibretroProcess.Info reset(){return core.reset();}
        public byte[] serialize(){return core.serialize();}
        public void restore(byte[] b){core.restore(b);}
        public byte[] memory(int id){return core.memory(id);}
        public LibretroSaveMemory saveMemory(){return core.saveMemory();}
        public void restoreSaveMemory(LibretroSaveMemory m){core.restoreSaveMemory(m);}
        public byte[] persistenceIdentity(){return core.persistenceIdentity();}
        public void close(){core.close();}
    }
    static final Map<Object,JniNetplaySession> runs=new ConcurrentHashMap<>();
    static final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
    static NetplayRelay<Object> relay;
    static byte[] rom,saved;
    static int checks,commits;
    static AtomicReference<Measured> measured=new AtomicReference<>();
    static void healthy(){for(var r:runs.values())if(r.error()!=null)throw new AssertionError(r.diagnostic());}
    static void waitFor(java.util.function.BooleanSupplier ready,Runnable tick)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!ready.getAsBoolean()&&System.nanoTime()<deadline){healthy();tick.run();Thread.sleep(10);}
        healthy();check(ready.getAsBoolean(),"condition "+(++checks));
    }
    static JniNetplaySession host(Object key,boolean resume) {
        var grant=relay.grant(key,0);
        var run=new JniNetplaySession(new NetplayProcess.Grant(891,grant.id(),true,true,0),()->rom,
                p->relay.receive(key,p),true,()->{var c=new Measured();measured.set(c);return c;});
        run.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){return resume?saved:null;}
            public CompletableFuture<Void> save(byte[] bytes){
                NetplaySaveState.decode(bytes,FcNetplaySaves.jniIdentity(true,NetplaySaveState.hash(rom)));
                saved=bytes.clone();commits++;return CompletableFuture.completedFuture(null);
            }
        });runs.put(key,run);run.start();return run;
    }
    static JniNetplaySession observer(){
        Object key=new Object();var grant=relay.grant(key,-1);
        var run=new JniNetplaySession(new NetplayProcess.Grant(891,grant.id(),false,false,-1),()->rom,
                p->wire.schedule(()->relay.receive(key,p),50,TimeUnit.MILLISECONDS),true);
        runs.put(key,run);run.start();return run;
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));rom=Files.readAllBytes(Path.of(args[1]));
        Object key=new Object();relay=new NetplayRelay<>(891,key,(target,packet)->wire.schedule(()->{
            var r=runs.get(target);if(r!=null)r.receive(packet.chunk(),packet.port());
        },50,TimeUnit.MILLISECONDS));
        wire.scheduleAtFixedRate(()->relay.renew(Set.copyOf(runs.keySet())),0,100,TimeUnit.MILLISECONDS);
        var h=host(key,false);AtomicLong sequence=new AtomicLong();
        try {
            waitFor(h::ready,()->{});var peer=observer();waitFor(peer::ready,()->{});
            int[][] phases={{128,120,0,1,1},{20,20,0,1,0},{128,120,0,0,1},{0,0,1,1,0},{0,0,1,0,0}};
            for(int[] p:phases){
                int aim=(p[2]!=0?65536:p[0]|p[1]<<8)|(p[3]<<17);long target=h.framesReceived()+125;
                waitFor(()->h.framesReceived()>=target,()->h.authoritativeGun(1,sequence.incrementAndGet(),0,aim));
                check(measured.get().trigger==p[3]*16,"native trigger "+p[3]);
                check(measured.get().light==p[4],"native light "+p[4]+" at "+p[0]+","+p[1]);
                if(p[0]==20){var late=observer();waitFor(late::ready,()->h.authoritativeGun(1,sequence.incrementAndGet(),0,aim));}
            }
            long timeoutFrame=h.framesReceived()+70;
            waitFor(()->h.framesReceived()>=timeoutFrame,()->{});
            check(measured.get().trigger==0&&measured.get().light==0,"timeout neutral, all spectators CRC healthy");
            h.saveNow().get(15,TimeUnit.SECONDS);check(saved!=null,"manual save");
            for(var r:runs.values())if(r!=h){r.close();r.terminated().get(15,TimeUnit.SECONDS);}
            h.close();h.terminated().get(30,TimeUnit.SECONDS);healthy();check(commits>=2,"final save");
            long initial=NetplaySaveState.decode(saved,FcNetplaySaves.jniIdentity(true,NetplaySaveState.hash(rom))).frame();
            runs.clear();relay.close();relay=new NetplayRelay<>(891,key,(to,p)->{});
            var restored=host(key,true);waitFor(restored::ready,()->{});
            waitFor(()->restored.framesReceived()>100,()->{});
            restored.saveNow().get(15,TimeUnit.SECONDS);
            check(NetplaySaveState.decode(saved,FcNetplaySaves.jniIdentity(true,NetplaySaveState.hash(rom))).frame()>initial+90,"gun save resumes advancing");
            restored.close();restored.terminated().get(30,TimeUnit.SECONDS);healthy();
            check(cn.piq.retro.libretro.jni.NativeLibretroBridge.availableSlots()==4,"all four JNI slots available");
            Files.write(root.resolve("gun-test-save.pns"),saved,StandardOpenOption.CREATE_NEW);
            System.out.println("GUN_NETPLAY_OK actual Mesen r2; three concurrent cores; 50ms each direction; CRC/late join/aim/trigger/offscreen/save/reopen");
        } finally {
            for(var r:runs.values())r.close();relay.close();wire.shutdownNow();
        }
    }
}
