// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.retroarch;

import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.retro.libretro.LibretroContentFiles;
import cn.piq.retro.libretro.LibretroProfile;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Autonomous, software-frame RetroArch session. This is not LibretroRuntime:
 * it provides no STEP, RESET, pause or live restore. Construct/bind/start/close and
 * presentation queries do not perform core or file IO on the caller's thread.
 * All declarations are trusted addon code, never values read from game/network manifests. */
public final class RetroArchSession implements AutoCloseable {
    /** Fixed companion runtime, extracted beside the core only after exact size/SHA checks. */
    public record Dependency(Class<?> owner, LibretroProfile.Artifact artifact, String fileName, long bytes) {
        public Dependency {
            Objects.requireNonNull(owner);Objects.requireNonNull(artifact);
            if(!Set.of("libc++.dll","libunwind.dll").contains(fileName)
                    ||bytes<1||bytes>64L*1024*1024||bytes>artifact.maxBytes())
                throw new IllegalArgumentException("Fixed RetroArch dependency");
        }
    }
    /** A larger core budget is explicit and bound to the same pinned resource/SHA as the profile. */
    public record Config(NetplayProfile profile, LibretroProfile.Artifact core, List<Dependency> dependencies) {
        public Config {
            Objects.requireNonNull(profile);Objects.requireNonNull(core);
            dependencies=List.copyOf(dependencies);
            if(profile.jni()!=null||profile.isFcZapper()||(profile.device()&255)!=1)
                throw new IllegalArgumentException("LOCAL requires a non-JNI digital-pad profile");
            if(!profile.resource().equals(core.resource())||!profile.sha().equalsIgnoreCase(core.sha256()))
                throw new IllegalArgumentException("LOCAL core must match its pinned profile");
            if(dependencies.size()>2)throw new IllegalArgumentException("Too many fixed runtime dependencies");
            var names=new HashSet<String>();
            for(var dependency:dependencies)if(!names.add(dependency.fileName()))
                throw new IllegalArgumentException("Duplicate runtime dependency");
        }
        public static Config of(NetplayProfile profile){
            return new Config(profile,new LibretroProfile.Artifact(profile.resource(),profile.sha(),128L*1024*1024),List.of());
        }
    }
    private final NetplayProcess process;
    /** The callable (including inspect/hash work) is invoked on the background owner. */
    public RetroArchSession(Config config,Callable<LibretroContentFiles> content){
        process=NetplayProcess.local(config,content);
    }
    public RetroArchSession(Config config,Callable<byte[]> content,Callable<Map<String,byte[]>> auxiliary){
        process=NetplayProcess.local(config,content,auxiliary);
    }
    /** Nonblocking latest/edge mailbox; sampled once per native PNPI, not once per rendered frame.
     * Must return promptly, with exactly profile.ports() masks in 0..4095. */
    public void inputProvider(Supplier<int[]> provider){process.localInputProvider(provider);}
    public void input(int port,int mask){process.localInput(port,mask);}
    public void release(int port){process.localRelease(port);}
    /** Bind before start. Caller retains save ownership, authorization and atomic persistence. */
    public void persistence(NetplayProcess.Persistence persistence){process.persistence(persistence);}
    public void start(){process.start();}
    public boolean ready(){return process.ready();}
    public NetplayProcess.Frame poll(){return process.poll();}
    public String error(){return process.error();}
    public String status(){return process.status();}
    public String diagnostic(){return process.diagnostic();}
    public String saveStatus(){return process.saveStatus();}
    public boolean canSave(){return process.canSave();}
    public CompletableFuture<Void> saveNow(){return process.saveNow();}
    public CompletableFuture<byte[]> checkpoint(){return process.checkpoint();}
    /** Success confirms child exit AND workspace removal, not graceful native deinitialization. */
    public CompletableFuture<Void> terminated(){return process.terminated();}
    public boolean forcedTermination(){return process.forcedTermination();}
    @Override public void close(){process.close();}
}
