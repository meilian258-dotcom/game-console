// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.libretro.LibretroContentFiles;
import cn.piq.retro.storage.RuntimeWorkspace;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;

/** Explicit isolated-JVM smoke, not a JUnit test, Minecraft acceptance or a commercial-game test.
 * Usage: java -cp <current classes and fixed resources> cn.piq.fcarcade.netplay.RetroArchLocalProbe <NEW output dir>
 * The output directory must not already exist. No runtime/core path argument or external ROM is accepted.
 * Two real LOCAL sessions use the pinned packaged RetroArch/Mesen and an original embedded test ROM.
 */
public final class RetroArchLocalProbe {
    private static final String RUNNER_SHA="1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1";
    private static final AtomicInteger checks=new AtomicInteger();
    private static final AtomicIntegerArray inputs=new AtomicIntegerArray(2);
    private static Path root;
    private static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);checks.incrementAndGet();System.out.println("CHECK "+label);
    }
    /** Original two-pad / battery NROM already used by the repository's JNI room diagnostic.
     * NES A on either pad switches the universal background palette; boot writes SRAM[0]=0x5a. */
    private static byte[] rom() {
        byte[] r=new byte[16+16384+8192];r[0]='N';r[1]='E';r[2]='S';r[3]=26;r[4]=1;r[5]=1;r[6]=2;r[8]=1;
        int[] code={0x78,0xd8,0xa2,0xff,0x9a,0xa9,0,0x8d,0,0x20,0x8d,1,0x20,0x2c,2,0x20,0x10,0xfb,0xa9,1,0x8d,0x16,0x40,0xa9,0,0x8d,0x16,0x40,0xad,0x16,0x40,0x29,1,0x85,0,0xad,0x17,0x40,0x29,1,0x05,0,0xf0,4,0xa9,0x30,0xd0,2,0xa9,0x0f,0x85,1,0xad,2,0x20,0xa9,0x3f,0x8d,6,0x20,0xa9,0,0x8d,6,0x20,0xa5,1,0x8d,7,0x20,0xa9,0,0x8d,6,0x20,0x8d,6,0x20,0x4c,0x0d,0x80};
        for(int i=0;i<code.length;i++)r[16+i]=(byte)code[i];
        int[] boot={0xa9,0x5a,0x8d,0,0x60,0x4c,0,0x80};for(int i=0;i<boot.length;i++)r[16+0x100+i]=(byte)boot[i];
        for(int i=0x3ffa;i<0x4000;i+=2){r[16+i]=0;r[16+i+1]=(byte)0x80;}r[16+0x3ffd]=(byte)0x81;return r;
    }
    private static final class DiskSave implements NetplayProcess.Persistence {
        final boolean resume;final AtomicInteger commits=new AtomicInteger(),finishes=new AtomicInteger();
        NetplaySaveStore store;NetplaySaveState.Identity identity;
        DiskSave(boolean resume){this.resume=resume;}
        public synchronized byte[] load(NetplaySaveState.Identity actual)throws Exception {
            check(actual.equals(NetplaySaveState.localIdentity(RetroArchSession.Config.of(NetplayProfile.fc()),
                    NetplaySaveState.hash(rom()),Map.of(),RUNNER_SHA)),"exact LOCAL runner/core/content save identity");
            identity=actual;store=new NetplaySaveStore(root.resolve("owned-save"),actual);
            byte[] saved=store.read();check(resume==(saved!=null),resume?"durable seed read":"new game has no old seed");return saved;
        }
        public synchronized CompletableFuture<Void> save(byte[] bytes) {
            try {
                var parts=NetplaySaveState.decode(bytes,identity);
                check(parts.state().length>0,"nonempty exact-core state");
                check(parts.ram().length==8192&&(parts.ram()[0]&255)==0x5a,"original battery SRAM preserved");
                store.write(bytes);commits.incrementAndGet();return CompletableFuture.completedFuture(null);
            }catch(Exception failure){return CompletableFuture.failedFuture(failure);}
        }
        public synchronized CompletableFuture<Void> finish() {
            try{if(store!=null){store.close();store=null;}finishes.incrementAndGet();return CompletableFuture.completedFuture(null);}
            catch(Exception failure){return CompletableFuture.failedFuture(failure);}
        }
        public synchronized void abort(){try{if(store!=null){store.close();store=null;}}catch(Exception ignored){}}
    }
    private static final class Run implements AutoCloseable {
        final RetroArchSession session;final DiskSave saves;
        String picture;long frame;long audioSamples;Path workspace;ProcessHandle child;Instant childStarted;
        Run(boolean resume,boolean invalid) {
            saves=invalid?null:new DiskSave(resume);
            var config=RetroArchSession.Config.of(NetplayProfile.fc());
            session=invalid?new RetroArchSession(config,()->new byte[16],Map::of)
                    :new RetroArchSession(config,()->LibretroContentFiles.inspect("content.nes",
                            Map.of("content.nes",root.resolve("source-original.nes")),config.profile().maxRomBytes(),16*1024*1024,()->{}));
            session.inputProvider(()->new int[]{inputs.get(0),inputs.get(1)});
            if(saves!=null)session.persistence(saves);session.start();
        }
        void poll() {
            if(session.error()!=null)throw new AssertionError(session.diagnostic());
            NetplayProcess.Frame next;
            while((next=session.poll())!=null) {
                frame=next.number();audioSamples+=next.stereo().length;
                if(next.rgba().length>0) {
                    if(next.rgba().length!=next.width()*next.height()*4)throw new AssertionError("RGBA size");
                    picture=NetplaySaveState.hash(next.rgba());
                }
                if(next.sampleRate()!=44100||next.rotation()!=0)throw new AssertionError("FC output metadata");
            }
        }
        void until(String label,BooleanSupplier condition)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
            while(System.nanoTime()<end){poll();if(condition.getAsBoolean()){check(true,label);return;}Thread.sleep(10);}
            throw new AssertionError("Timeout: "+label+"\n"+session.diagnostic());
        }
        void observeOwnedProcess()throws Exception {
            Path sessions=root.resolve("instance/game-console/runtime-sessions");
            try(var paths=Files.list(sessions)) {
                var candidates=paths.filter(p->p.getFileName().toString().startsWith("netplay-")).toList();
                check(candidates.size()==1,"one caller-owned workspace");workspace=candidates.getFirst();
            }
            var identity=Files.readAllLines(workspace.resolve("child"));
            check(identity.size()==2&&!identity.get(1).equals("unknown"),"native process start identity available");
            child=ProcessHandle.of(Long.parseLong(identity.get(0))).orElseThrow();childStarted=Instant.parse(identity.get(1));
            check(child.isAlive()&&child.info().startInstant().orElseThrow().equals(childStarted),"exact native child alive");
            // Windows JDK exposes the actual command line but not necessarily a parsed argv.
            // Either source is an OS observation of this exact child, never the planned launch list.
            var processInfo=child.info();
            String[] args;
            if(processInfo.arguments().isPresent()) args=processInfo.arguments().get();
            else args=processInfo.commandLine().orElseGet(()->windowsChildCommand(child.pid())).split("\\s+");
            check(child.isAlive()&&child.info().startInstant().orElseThrow().equals(childStarted),"same native child after OS command inspection");
            for(String arg:args)for(String forbidden:List.of("--host","--connect","--port","--nick","--check-frames"))
                check(!arg.replace("\"", "").startsWith(forbidden),"native argv excludes "+forbidden);
            String config=Files.readString(workspace.resolve("retroarch.cfg"));
            check(!config.contains("netplay_"),"actual native config has no Netplay settings");
            check(config.contains("network_cmd_enable = \"false\""),"actual native network command server disabled");
            check(NetplaySaveState.hash(Files.readAllBytes(workspace.resolve("retroarch.exe"))).equals(RUNNER_SHA),"actual extracted runner pin");
            check(NetplaySaveState.hash(Files.readAllBytes(workspace.resolve("core.dll"))).equals(NetplayProfile.fc().sha()),"actual extracted core pin");
        }
        public void close()throws Exception {
            session.close();session.terminated().get(45,TimeUnit.SECONDS);
            check(!session.ready(),"not ready after actual termination");
            if(child!=null)check(!child.isAlive()||!child.info().startInstant().orElse(Instant.MIN).equals(childStarted),"exact native child has exited");
            if(workspace!=null)check(Files.notExists(workspace),"workspace removed only after child exit");
            System.out.println("NATIVE_CLOSE forced="+session.forcedTermination()+"; child exit is not graceful core-deinit evidence");
        }
    }
    private static String windowsChildCommand(long pid) {
        if(!System.getProperty("os.name", "").startsWith("Windows")||pid<=0)
            throw new AssertionError("OS child command line unavailable");
        Process query=null;
        try {
            // Read this probe's exact owned PID only. No process discovery, attach or game operation.
            query=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",
                    "(Get-CimInstance Win32_Process -Filter 'ProcessId = "+pid+"').CommandLine")
                    .redirectErrorStream(true).start();
            if(!query.waitFor(15,TimeUnit.SECONDS))throw new IOException("Owned-process command query timed out");
            byte[] bytes=query.getInputStream().readNBytes(16385);
            String value=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).trim();
            if(query.exitValue()!=0||bytes.length>16384||!value.contains("retroarch.exe")
                    ||!value.contains("core.dll")||!value.contains("content.nes"))
                throw new IOException("Owned-process command query did not return the actual launch");
            return value;
        }catch(Exception failure){throw new AssertionError("Cannot verify actual native command line",failure);}
        finally{if(query!=null&&query.isAlive())query.destroyForcibly();}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected one NEW output directory");
        root=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectory(root);Files.createDirectory(root.resolve("instance"));
        Files.write(root.resolve("source-original.nes"),rom(),StandardOpenOption.CREATE_NEW);
        RuntimeWorkspace.configure(root.resolve("instance"));
        System.out.println("ISOLATED RETROARCH LOCAL SMOKE; NOT MINECRAFT / NETPLAY / LONG-GAME ACCEPTANCE");
        try {
            for(int generation=0;generation<2;generation++) {
                inputs.set(0,0);inputs.set(1,0);
                try(var run=new Run(generation>0,false)) {
                    run.until("LOCAL frames ready",()->run.session.ready()&&run.frame>40&&run.picture!=null);
                    run.observeOwnedProcess();String neutral=run.picture;
                    for(int port=0;port<2;port++) {
                        final long start=run.frame;inputs.set(port,256);
                        run.until("pad "+(port+1)+" A changes real picture",()->run.frame>start+10&&!neutral.equals(run.picture));
                        inputs.set(port,0);
                        run.until("pad "+(port+1)+" release returns to neutral",()->neutral.equals(run.picture));
                    }
                    check(run.audioSamples>0,"real PCM samples received");
                    run.session.saveNow().get(20,TimeUnit.SECONDS);check(run.saves.commits.get()>0,"manual durable save confirmed");
                    if(generation>0)check(run.session.diagnostic().contains("Checkpoint restored before Netplay initialization"),"native accepted exact LOCAL seed before running");
                    int before=run.saves.commits.get();run.close();
                    check(run.saves.commits.get()>before&&run.saves.finishes.get()==1,"final durable commit precedes finish");
                }
                check(Arrays.equals(rom(),Files.readAllBytes(root.resolve("source-original.nes"))),"original content unchanged after cleanup");
            }
            try(var invalid=new Run(false,true)) {
                invalid.session.terminated().get(40,TimeUnit.SECONDS);
                check(invalid.session.error()!=null&&!invalid.session.ready(),"native rejects malformed ROM without JNI fallback");
            }
            try(var paths=Files.list(root.resolve("instance/game-console/runtime-sessions"))) {
                check(paths.noneMatch(p->p.getFileName().toString().startsWith("netplay-")),"no session workspaces remain after success and rejection");
            }
            Files.writeString(root.resolve("result.txt"),"PASS checks="+checks.get()+"\nScope: pinned RetroArch LOCAL/Mesen; original NROM; two pads, state/SRAM, reopen, failed load and owned-process cleanup.\nNot Netplay, Minecraft, arcade, graceful core deinit or long-session acceptance.\n",StandardOpenOption.CREATE_NEW);
            System.out.println("PASS "+checks.get()+" checks");
        }catch(Throwable failure) {
            Files.writeString(root.resolve("failure.txt"),failure.toString(),StandardOpenOption.CREATE_NEW);throw failure;
        }
    }
}
