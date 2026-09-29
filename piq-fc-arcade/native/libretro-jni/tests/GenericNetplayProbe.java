// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Packaged, real-core two-pad test. Original diagnostic ROM only; not a Minecraft test. */
public final class GenericNetplayProbe {
    static final Map<Object,NetplayProcess> runs=new ConcurrentHashMap<>();
    static final ScheduledThreadPoolExecutor wire=new ScheduledThreadPoolExecutor(1);
    static NetplayRelay<Object> relay;
    static NetplayProfile profile;
    static byte[] content;
    static volatile byte[] saved;
    static volatile int commits;
    static void healthy(){for(var r:runs.values())if(r.error()!=null)throw new AssertionError(r.diagnostic());}
    static void until(java.util.function.BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!condition.getAsBoolean()&&System.nanoTime()<end){healthy();Thread.sleep(10);}
        healthy();if(!condition.getAsBoolean())throw new AssertionError("Timed out: "+runs.values().stream().map(NetplayProcess::diagnostic).toList()+" relay="+relay.lastRejection());
    }
    static NetplayProcess start(Object key,int port,boolean host,boolean resume) {
        var ticket=relay.grant(key,port);
        var run=new NetplayProcess(new NetplayProcess.Grant(911,ticket.id(),host,port>=0,port),()->content,
                packet->{if(host)relay.receive(key,packet);else wire.schedule(()->relay.receive(key,packet),50,TimeUnit.MILLISECONDS);},profile,Map::of);
        if(host)run.persistence(new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){return resume?saved:null;}
            public CompletableFuture<Void> save(byte[] bytes){
                NetplaySaveState.decode(bytes,NetplaySaveState.identity(profile,NetplaySaveState.hash(content),Map.of()));
                saved=bytes.clone();commits++;return CompletableFuture.completedFuture(null);
            }
        });
        runs.put(key,run);run.start();return run;
    }
    static void stop(Object key)throws Exception {
        var r=runs.get(key);r.close();until(()->!r.ready());
        // NetplayProcess deliberately exposes asynchronous close, not the native handle.
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!r.status().equals("已结束")&&System.nanoTime()<end){if(r.error()!=null)throw new AssertionError(r.diagnostic());Thread.sleep(10);}
        if(!r.status().equals("已结束"))throw new AssertionError("close unconfirmed "+r.diagnostic());
        relay.revoke(key);runs.remove(key);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root.resolve("instance"));RuntimeWorkspace.configure(root.resolve("instance"));
        content=Files.readAllBytes(Path.of(args[1]));
        profile=(NetplayProfile)Class.forName(args[2]).getMethod("profile").invoke(null);
        if(profile.jni()==null)throw new AssertionError("not a JNI declaration");
        Object hostKey=new Object(),p2Key=new Object(),watchKey=new Object();
        relay=new NetplayRelay<>(911,hostKey,(target,packet)->wire.schedule(()->{var r=runs.get(target);if(r!=null)r.receive(packet.chunk(),packet.port());},50,TimeUnit.MILLISECONDS));
        wire.scheduleAtFixedRate(()->relay.renew(Set.copyOf(runs.keySet())),0,100,TimeUnit.MILLISECONDS);
        try {
            var host=start(hostKey,0,true,false);until(host::ready);
            var p2=start(p2Key,1,false,false);until(p2::ready);
            host.inputRetroPad(256);p2.inputRetroPad(1);until(()->p2.framesReceived()>125);
            host.inputRetroPad(16);p2.inputRetroPad(128);until(()->p2.framesReceived()>250);
            var watch=start(watchKey,-1,false,false);until(watch::ready);until(()->watch.framesReceived()>125);
            var picture=watch.poll();if(picture==null||picture.width()<1||picture.stereo().length==0||picture.sampleRate()!=48000)throw new AssertionError("generic AV output");
            stop(p2Key);p2Key=new Object();var rejoin=start(p2Key,1,false,false);until(rejoin::ready);rejoin.inputRetroPad(512);until(()->rejoin.framesReceived()>125);
            host.saveNow().get(20,TimeUnit.SECONDS);if(saved==null)throw new AssertionError("manual save missing");
            stop(watchKey);stop(p2Key);stop(hostKey);if(commits<2)throw new AssertionError("final save missing");
            long before=NetplaySaveState.decode(saved,NetplaySaveState.identity(profile,NetplaySaveState.hash(content),Map.of())).frame();
            relay=new NetplayRelay<>(911,hostKey,(target,packet)->{});
            var reopened=start(hostKey,0,true,true);until(reopened::ready);until(()->reopened.framesReceived()>100);reopened.saveNow().get(20,TimeUnit.SECONDS);
            if(NetplaySaveState.decode(saved,NetplaySaveState.identity(profile,NetplaySaveState.hash(content),Map.of())).frame()<before+90)throw new AssertionError("resume frame");
            stop(hostKey);if(NativeLibretroBridge.availableSlots()!=4)throw new AssertionError("native leak");
            Files.write(root.resolve("generic-save.pns"),saved,StandardOpenOption.CREATE_NEW);
            System.out.println("GENERIC_JNI_NETPLAY_OK "+profile.jni().name()+"; two pads; 50ms each direction; late observer; reconnect; CRC; stereo; manual/final save/reopen");
        } finally {for(var r:runs.values())r.close();wire.shutdownNow();relay.close();}
    }
}
