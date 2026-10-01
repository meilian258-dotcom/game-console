// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.fcarcade.netplay.*;
import cn.piq.nativearcade.NativeNetplayProfile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated real MAME media / FBNeo cabinet authority probes; no game content is packaged. */
public final class JniArcadeProbe {
    static final Map<Object,NetplayProcess> runs=new ConcurrentHashMap<>();
    static final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
    static NetplayRelay<Object> relay;
    static volatile boolean cleaning;
    static void send(Runnable action){if(!cleaning)wire.schedule(action,50,TimeUnit.MILLISECONDS);}
    static void diagnostics(){runs.forEach((key,r)->System.out.println("NETPLAY_DIAGNOSTIC "+r.diagnostic()));System.out.println("RELAY_REJECTION "+relay.lastRejection());}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void until(java.util.function.BooleanSupplier ready)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!ready.getAsBoolean()&&System.nanoTime()<end){healthy();Thread.sleep(10);}
        healthy();check(ready.getAsBoolean(),"timeout "+runs.values().stream().map(NetplayProcess::diagnostic).toList()+" relay="+relay.lastRejection());
    }
    static void healthy(){for(var r:runs.values()){check(r.error()==null,r.diagnostic());check(r.diagnostic().contains("拒绝异常连接：0"),r.diagnostic());}check(relay.lastRejection().isEmpty(),"relay="+relay.lastRejection());}
    static void stop(Object key)throws Exception {var r=runs.get(key);r.close();until(()->r.status().equals("已结束"));relay.revoke(key);runs.remove(key);}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),rom=Path.of(args[2]).toAbsolutePath();Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
        if(args[1].equals("media")){
            Set<Integer> crcs=new HashSet<>();int audio=0;var r=new NativeJniMediaSession(root,rom);
            try {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);int frames=0;
                while(frames<180&&System.nanoTime()<end){check(r.error()==null,String.valueOf(r.error()));r.offerInput((frames/30&1)==0?4:1,frames<90?0:16);
                    var f=r.pollFrame();if(f!=null){frames++;check(f.abgr().length==f.width()*f.height(),"geometry");crcs.add(Arrays.hashCode(f.abgr()));audio+=f.pcm48k().length;}Thread.sleep(5);}
                check(frames==180&&crcs.size()>2&&audio>0,"MAME media frames/audio");
            }finally{r.close();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!r.isTerminated()&&System.nanoTime()<end)Thread.sleep(10);}
            check(r.isTerminated()&&r.error()==null,"MAME close: "+r.error());check(NativeLibretroBridge.availableSlots()==4,"slot release");
            System.out.println("MAME_JNI_MEDIA_OK frames=180 differentPictures="+crcs.size()+" pcmShorts="+audio);return;
        }
        var profile=NativeNetplayProfile.profile(rom.getFileName().toString());
        String testCoreSha=System.getProperty("gc.probe.coreSha");
        if(testCoreSha!=null){var p=profile.jni();String resource="/native-runtime/qa/fbneo.dll";
            var runtime=new cn.piq.retro.libretro.LibretroProfile(p.name(),p.extension(),p.fullPath(),p.devices(),p.mesenGun(),p.options(),Map.of("windows-x64",new cn.piq.retro.libretro.LibretroProfile.Artifact(resource,testCoreSha)));
            profile=new NetplayProfile(profile.owner(),resource,testCoreSha,profile.contentName(),profile.options(),profile.device(),profile.sampleRate(),profile.maxRomBytes(),profile.ports()).withJni(runtime);}
        byte[] content=Files.readAllBytes(rom);var extras=new TreeMap<String,byte[]>();
        for(String name:List.of("pgm.zip","neogeo.zip","qsound_hle.zip","qsound.zip")){Path p=rom.getParent().resolve(name);if(Files.isRegularFile(p))extras.put(name,Files.readAllBytes(p));}
        var hashes=new TreeMap<String,String>();extras.forEach((n,b)->hashes.put(n,NetplaySaveState.hash(b)));var identity=NetplaySaveState.identity(profile,NetplaySaveState.hash(content),hashes);
        byte[][] save={null};int[] commits={0};Object hostKey=new Object();var keys=List.of(hostKey,new Object(),new Object(),new Object());
        relay=new NetplayRelay<>(950,hostKey,(target,p)->send(()->{var r=runs.get(target);if(r!=null)r.receive(p.chunk(),p.port());}),4);
        var firstRelay=relay;
        wire.scheduleAtFixedRate(()->relay.renew(Set.copyOf(runs.keySet())),0,100,TimeUnit.MILLISECONDS);
        try {
            for(int p=0;p<4;p++){
                Object key=keys.get(p);var ticket=relay.grant(key,p);boolean host=p==0;
                var r=new NetplayProcess(new NetplayProcess.Grant(950,ticket.id(),host,true,p),()->content,
                    packet->{if(host)firstRelay.receive(key,packet);else send(()->firstRelay.receive(key,packet));},profile,()->extras,true,true);
                if(host)r.persistence(new NetplayProcess.Persistence(){public byte[] load(NetplaySaveState.Identity i){check(identity.equals(i),"content/core/BIOS identity");return null;}
                    public CompletableFuture<Void> save(byte[] b){NetplaySaveState.decode(b,identity);save[0]=b.clone();commits[0]++;return CompletableFuture.completedFuture(null);}});
                runs.put(key,r);r.start();until(r::ready);
            }
            var host=runs.get(hostKey);long before=host.framesReceived();long sequence=0;
            var starts=new IdentityHashMap<Object,Long>();runs.forEach((k,r)->starts.put(k,r.framesReceived()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(host.framesReceived()<before+900&&System.nanoTime()<deadline){healthy();for(int p=0;p<4;p++)host.cabinetInput(p,(host.framesReceived()/30&1)==0?8|1:16|256);
                if(sequence<4){host.cabinetCoin((int)sequence,++sequence);}Thread.sleep(10);}
            healthy();check(host.framesReceived()>=before+900,"host progress deadline");
            for(var entry:runs.entrySet())check(entry.getValue().framesReceived()-starts.get(entry.getKey())>=850,"peer did not advance with host: "+entry.getValue().diagnostic());
            diagnostics();
            host.saveNow().get(20,TimeUnit.SECONDS);check(save[0]!=null,"cabinet server save");
            for(int p=1;p<4;p++)stop(keys.get(p));stop(hostKey);check(commits[0]>=2,"cabinet final save");
            check(NativeLibretroBridge.availableSlots()==4,"all slots released");Files.write(root.resolve("cabinet-save.pns"),save[0],StandardOpenOption.CREATE_NEW);
            byte[] saved=save[0].clone();long savedFrame=NetplaySaveState.decode(saved,identity).frame();
            firstRelay.close();Object reopenKey=new Object();
            var reopenRelay=new NetplayRelay<Object>(951,reopenKey,(target,p)->send(()->{var r=runs.get(target);if(r!=null)r.receive(p.chunk(),p.port());}),4);
            relay=reopenRelay;var ticket=reopenRelay.grant(reopenKey,0);boolean[] loaded={false};
            var reopened=new NetplayProcess(new NetplayProcess.Grant(951,ticket.id(),true,true,0),()->content,
                    packet->reopenRelay.receive(reopenKey,packet),profile,()->extras,true,true);
            reopened.persistence(new NetplayProcess.Persistence(){
                public byte[] load(NetplaySaveState.Identity i){check(identity.equals(i),"reopen identity");loaded[0]=true;return saved.clone();}
                public CompletableFuture<Void> save(byte[] b){check(NetplaySaveState.decode(b,identity).frame()>savedFrame,"reopened save must advance");return CompletableFuture.completedFuture(null);}
            });
            runs.put(reopenKey,reopened);reopened.start();until(reopened::ready);until(()->reopened.framesReceived()>=180);
            check(loaded[0],"fresh native instance loaded persisted checkpoint");reopened.saveNow().get(20,TimeUnit.SECONDS);stop(reopenKey);
            check(NativeLibretroBridge.availableSlots()==4,"reopen releases all slots");
            System.out.println("METRIC\tsaveReopenPassed\ttrue");
            System.out.println("ARCADE_JNI_NETPLAY_OK four cores; four server-authorized ports; 50ms one way; CRC; paid coin; manual/final save; fresh-instance reopen; "+identity);
        }catch(Throwable t){diagnostics();throw t;}
        finally{
            // Keep the original test failure: asynchronous close must not submit to a terminated wire.
            cleaning=true;runs.values().forEach(NetplayProcess::close);relay.close();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(NativeLibretroBridge.availableSlots()!=4&&System.nanoTime()<end)Thread.sleep(10);
            wire.shutdownNow();System.out.println("METRIC\tfreeNativeSlots\t"+NativeLibretroBridge.availableSlots());
        }
    }
}
