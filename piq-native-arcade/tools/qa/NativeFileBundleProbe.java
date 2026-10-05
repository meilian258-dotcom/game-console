// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.netplay.*;
import cn.piq.nativearcade.client.NativeNetplayContent;
import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in final-JAR, independent JVM probe. User ROM/BIOS inputs are read-only, never packaged.
 * Run with -Xmx256m to bound Java heap for four native owners of one room. Each owner still
 * retains a bounded native content copy plus emulator allocations; this is not a total-RAM bound. */
public final class NativeFileBundleProbe {
    static final Map<Object,NetplayProcess> runs=new ConcurrentHashMap<>();
    static NetplayRelay<Object> relay;
    static long maximumHeap;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void heap(){var r=Runtime.getRuntime();maximumHeap=Math.max(maximumHeap,r.totalMemory()-r.freeMemory());}
    static void await(java.util.function.BooleanSupplier done)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(!done.getAsBoolean()&&System.nanoTime()<end){
            if(relay!=null)relay.renew(Set.copyOf(runs.keySet()));
            for(var r:runs.values())check(r.error()==null,r.diagnostic());heap();Thread.sleep(5);
        }
        for(var r:runs.values())check(r.error()==null,r.diagnostic());
        check(done.getAsBoolean(),"timeout "+runs.values().stream().map(NetplayProcess::diagnostic).toList());
    }
    static long workspaces(Path instance)throws IOException {
        Path root=instance.resolve("game-console/runtime-sessions");if(!Files.isDirectory(root))return 0;
        // The JNI bridge DLL is intentionally pinned for this JVM's lifetime. Only core-owner
        // workspaces must disappear when failed preparations/sessions terminate.
        try(var paths=Files.list(root)){return paths.filter(Files::isDirectory)
            .filter(p->!Files.isRegularFile(p.resolve("piq-libretro-jni.dll"),LinkOption.NOFOLLOW_LINKS)).count();}
    }
    static void failureCleanup(Path evidence,LibretroProfile profile)throws Exception {
        Path source=evidence.resolve("mutable-fixture.zip");Files.write(source,new byte[32],StandardOpenOption.CREATE_NEW);
        var files=LibretroContentFiles.inspect("fixture.zip",Map.of("fixture.zip",source),1024,1024,()->{});
        byte[] changed=new byte[32];changed[0]=7;Files.write(source,changed);
        try(var runtime=new LibretroJniRuntime(profile,NativeNetplayContent.class)){
            try{runtime.loadFiles(files,()->{});throw new AssertionError("Changed SHA accepted");}
            catch(RuntimeException expected){check(String.valueOf(expected).contains("hash changed"),String.valueOf(expected));}
            check(!runtime.nativeSlotHeld(),"failed SHA retained native reservation");
        }
        check(NativeLibretroBridge.availableSlots()==4,"SHA failure slot leak");
        files=LibretroContentFiles.inspect("fixture.zip",Map.of("fixture.zip",source),1024,1024,()->{});
        var steps=new AtomicInteger();
        try(var runtime=new LibretroJniRuntime(profile,NativeNetplayContent.class)){
            try{runtime.loadFiles(files,()->{if(steps.incrementAndGet()>=3)throw new IOException("probe-stage-cancelled");});throw new AssertionError("Cancellation ignored");}
            catch(RuntimeException expected){check(String.valueOf(expected).contains("probe-stage-cancelled"),String.valueOf(expected));}
            check(!runtime.nativeSlotHeld(),"cancel retained native reservation");
        }
        check(NativeLibretroBridge.availableSlots()==4,"cancel slot leak");
    }
    public static void main(String[] args)throws Exception {
        Path evidence=Path.of(args[0]).toAbsolutePath().normalize(),rom=Path.of(args[1]).toAbsolutePath().normalize();
        check(!Files.exists(evidence),"Refuse to overwrite previous QA evidence");Path instance=Files.createDirectories(evidence.resolve("instance"));RuntimeWorkspace.configure(instance);
        var content=NativeNetplayContent.loadFiles(rom);check(content.rom().length==0&&content.auxiliary().isEmpty(),"ROM-sized arrays retained");
        var files=content.files();var profile=content.profile();check(files.main().size()>64*1024*1024,"Use a real >64 MiB arcade ZIP");
        failureCleanup(evidence,profile.jni());await(()->{try{return workspaces(instance)==0;}catch(IOException e){throw new UncheckedIOException(e);}});
        Object hostKey=new Object();relay=new NetplayRelay<>(1004,hostKey,(key,p)->{var r=runs.get(key);if(r!=null)r.receive(p.chunk(),p.port());},4);
        var identity=NetplaySaveState.identity(profile,files.main().sha256(),files.auxiliaryHashes());
        var saved=new AtomicReference<byte[]>();var commits=new AtomicInteger();NetplayProcess host=null;
        try {
            for(int i=0;i<4;i++){
                Object key=i==0?hostKey:new Object();int port=i>=2?-1:i;var grant=relay.grant(key,port);
                var r=new NetplayProcess(new NetplayProcess.Grant(1004,grant.id(),i==0,port>=0,port),
                    ()->{throw new AssertionError("Large file routed through ROM byte[]");},p->relay.receive(key,p),profile,
                    ()->{throw new AssertionError("BIOS byte[] called");},true,true);
                r.fileContent(files);
                if(i==0){host=r;r.persistence(new NetplayProcess.Persistence(){
                    public byte[] load(NetplaySaveState.Identity actual){check(identity.equals(actual),"Save identity changed");return null;}
                    public CompletableFuture<Void> save(byte[] bytes){NetplaySaveState.decode(bytes,identity);saved.set(bytes);commits.incrementAndGet();return CompletableFuture.completedFuture(null);}
                });}
                runs.put(key,r);r.start();await(r::ready);heap();
            }
            check(NativeLibretroBridge.availableSlots()==0,"Four real core slots not held");
            var opened=host;long before=opened.framesReceived();await(()->opened.framesReceived()>before+180);
            for(var r:runs.values()){check(r.framesReceived()>90,"peer/observer did not advance");check(r.poll()!=null,"no video");}
            var participantFrames=runs.values().stream().map(NetplayProcess::framesReceived).sorted().toList();
            host.saveNow().get(20,TimeUnit.SECONDS);check(saved.get()!=null,"No manual checkpoint");
            for(var r:runs.values())if(r!=host){r.close();r.terminated().get(20,TimeUnit.SECONDS);}
            host.close();host.terminated().get(20,TimeUnit.SECONDS);check(commits.get()>=2,"Final save not committed");
            check(NativeLibretroBridge.availableSlots()==4,"Native slots leaked after four owners");
            long roomFrames=opened.framesReceived();
            Path checkpoint=evidence.resolve("checkpoint.pns");Files.write(checkpoint,saved.get(),StandardOpenOption.CREATE_NEW);
            long savedFrame=NetplaySaveState.decode(saved.get(),identity).frame();
            runs.clear();relay.close();Object reopenedKey=new Object();
            relay=new NetplayRelay<>(1005,reopenedKey,(key,p)->{var r=runs.get(key);if(r!=null)r.receive(p.chunk(),p.port());},4);
            var ticket=relay.grant(reopenedKey,0);var loaded=new AtomicBoolean();var reopenCommits=new AtomicInteger();
            var reopened=new NetplayProcess(new NetplayProcess.Grant(1005,ticket.id(),true,true,0),
                ()->{throw new AssertionError("Reopen used byte[] content");},p->relay.receive(reopenedKey,p),profile,
                ()->{throw new AssertionError("Reopen used BIOS arrays");},true,true);
            reopened.fileContent(files);reopened.persistence(new NetplayProcess.Persistence(){
                public byte[] load(NetplaySaveState.Identity actual)throws Exception {
                    check(identity.equals(actual),"Reopened identity mismatch");byte[] bytes=Files.readAllBytes(checkpoint);
                    check(NetplaySaveState.decode(bytes,actual).frame()==savedFrame,"Reopened checkpoint changed");loaded.set(true);return bytes;
                }
                public CompletableFuture<Void> save(byte[] bytes){
                    check(NetplaySaveState.decode(bytes,identity).frame()>savedFrame,"Reopened save did not advance");
                    try{Files.write(evidence.resolve("reopened-save-"+reopenCommits.incrementAndGet()+".pns"),bytes,StandardOpenOption.CREATE_NEW);return CompletableFuture.completedFuture(null);}
                    catch(IOException e){return CompletableFuture.failedFuture(e);}
                }
            });
            runs.put(reopenedKey,reopened);reopened.start();await(reopened::ready);await(()->reopened.framesReceived()>90);
            check(loaded.get(),"Fresh native owner never read checkpoint");reopened.saveNow().get(20,TimeUnit.SECONDS);
            reopened.close();reopened.terminated().get(20,TimeUnit.SECONDS);check(reopenCommits.get()>=2,"Reopen manual/final save absent");
            check(NativeLibretroBridge.availableSlots()==4,"Reopened native slot leaked");
            var after=NativeNetplayContent.loadFiles(rom);check(files.files().equals(after.files().files()),"Source ROM/BIOS changed");
            await(()->{try{return workspaces(instance)==0;}catch(IOException e){throw new UncheckedIOException(e);}});
            String json="{\"ok\":true,\"realJni\":true,\"liveMinecraftVerified\":false,\"mainBytes\":"+files.main().size()+
                ",\"fileCount\":"+files.files().size()+",\"nativeSlots\":4,\"sampledMaxHeapBytes\":"+maximumHeap+
                ",\"heapLimitBytes\":"+Runtime.getRuntime().maxMemory()+",\"manualAndFinalSaves\":"+commits.get()+
                ",\"participants\":\"1 host + 1 peer + 2 seatless observers\",\"participantFrames\":"+participantFrames+
                ",\"saveProfileIdentity\":\""+identity.profile()+"\",\"saveContentIdentity\":\""+identity.content()+"\""+
                ",\"roomHostFrames\":"+roomFrames+",\"savedFrame\":"+savedFrame+",\"reopenedFrames\":"+reopened.framesReceived()+
                ",\"reopenManualAndFinalSaves\":"+reopenCommits.get()+",\"coreSha256\":\""+profile.sha()+
                "\",\"romSha256\":\""+files.main().sha256()+"\",\"failedLoadAndCancelledStageCleaned\":true,\"sourcesUnchanged\":true,\"freeNativeSlots\":4}";
            Files.writeString(evidence.resolve("result.json"),json+"\n",StandardOpenOption.CREATE_NEW);System.out.println(json);
        } finally {
            for(var r:runs.values())r.close();for(var r:runs.values())try{r.terminated().get(20,TimeUnit.SECONDS);}catch(Exception e){System.out.println("CLOSE_FAILURE="+e);}
            relay.close();System.out.println("FREE_NATIVE_SLOTS="+NativeLibretroBridge.availableSlots());
        }
    }
}
