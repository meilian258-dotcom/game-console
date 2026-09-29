// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeSnapshotProfile;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One request-driven child for the pinned snapshot core, sharing the existing native process lease. */
public final class NativeSnapshotSession implements AutoCloseable {
    private static final long TIMEOUT=TimeUnit.SECONDS.toNanos(15);
    private final Object lifecycle=new Object(),calls=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),reaping=new AtomicBoolean();
    private final StringBuilder diagnostics=new StringBuilder();
    private volatile boolean starting=true;private volatile Process child;private volatile long deadline;
    private volatile String error;private NativeSnapshotWorkspace workspace;
    private DataInputStream in;private DataOutputStream out;private long request,frame;
    private NativeStepProtocol.Hello hello;

    /** Construct on the dedicated sync worker. The registrar only accepts a thread-safe cancellation signal. */
    public NativeSnapshotSession(Path runtimeDirectory,Path rom,Consumer<Runnable> cancellationRegistrar)throws IOException{
        Path runtime=Objects.requireNonNull(runtimeDirectory).toAbsolutePath().normalize();
        Path source=Objects.requireNonNull(rom).toAbsolutePath().normalize();Objects.requireNonNull(cancellationRegistrar);
        if(!System.getProperty("os.name","").startsWith("Windows")||!System.getProperty("os.arch","").matches("amd64|x86_64"))throw new IOException("本地同步运行库仅支持 Windows x64");
        if(!NativeSnapshotProfile.ROMS.containsKey(source.getFileName().toString()))throw new IOException("本地同步仅支持已验证的 kof97.zip / mslug2.zip；请为其它游戏选择传画面");
        NativeProcessSession.acquireStepSlot();
        try{
            cancellationRegistrar.accept(this::close);checkOpen();
            Path helper=runtime.resolve(NativeSnapshotProfile.HELPER_NAME),core=runtime.resolve(NativeSnapshotProfile.CORE_NAME),jna=runtime.resolve(NativeSnapshotProfile.JNA_NAME);
            NativeSnapshotWorkspace.verify(helper,NativeSnapshotProfile.HELPER_SHA,NativeSnapshotProfile.HELPER_BYTES,closed::get);
            NativeSnapshotWorkspace.verify(core,NativeSnapshotProfile.CORE_SHA,NativeSnapshotProfile.CORE_BYTES,closed::get);
            NativeSnapshotWorkspace.verify(jna,NativeSnapshotProfile.JNA_SHA,NativeSnapshotProfile.JNA_BYTES,closed::get);
            workspace=new NativeSnapshotWorkspace(source);Path staged=workspace.stage(source,closed::get);checkOpen();
            ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx256m","-Djna.nosys=true","-cp",helper+File.pathSeparator+jna,
                "cn.piq.nativearcade.bridge.LabCoreWorker",core.toString(),NativeSnapshotProfile.CORE_SHA,staged.toString()).directory(workspace.directory().toFile());
            builder.environment().put("TZ","UTC0");builder.environment().remove("PIQ_LAB_DUMP_SCHEMA");
            synchronized(lifecycle){checkOpen();child=NativeProcessSession.launchStepChild(builder);checkOpen();
                in=new DataInputStream(new BufferedInputStream(child.getInputStream(),65536));out=new DataOutputStream(new BufferedOutputStream(child.getOutputStream(),65536));}
            Process exact=child;daemon("PIQ snapshot log",()->drain(exact.getErrorStream()));
            begin();daemon("PIQ snapshot deadline",this::watch);hello=NativeStepProtocol.readHello(in);end();checkOpen();
            if(Double.compare(hello.fps(),NativeSnapshotProfile.FPS)!=0||hello.sampleRate()!=NativeSnapshotProfile.SAMPLE_RATE||hello.maxPorts()!=4)throw new IOException("Experimental native timing differs from verified profile");
            NativeStepProtocol.Frame last=null;
            for(int n=0;n<NativeSnapshotProfile.BOOTSTRAP_FRAMES;n++)last=step(0,0);
            if(last==null||!last.hasVideo()||last.width()!=320||last.height()!=224||last.rotation()!=0||Float.compare(last.displayAspect(),4f/3f)!=0)throw new IOException("Experimental native bootstrap geometry differs from verified profile");
        }catch(Throwable failure){
            error=failure.toString();close();
            if(failure instanceof Error fatal)throw fatal;if(failure instanceof IOException io)throw io;throw new IOException("本地同步核心启动失败",failure);
        }finally{
            synchronized(lifecycle){starting=false;}
            if(closed.get())reap();
        }
    }
    public NativeStepProtocol.Hello hello(){return hello;}public long frame(){return frame;}
    public String romHash(){return workspace.romHash();}public String game(){return workspace.game();}
    public long ownedPid(){Process exact=child;return exact==null?-1:exact.pid();}
    public String error(){return error;}
    public String diagnostics(){synchronized(diagnostics){return diagnostics.toString();}}
    public NativeStepProtocol.Frame step(int p1,int p2)throws IOException{
        synchronized(calls){var value=call(NativeStepProtocol.step(next(),p1,p2,0,0));
            if(!(value instanceof NativeStepProtocol.Frame result)||result.frame()!=frame+1)throw broken("Native step frame mismatch");
            if(Double.compare(result.fps(),NativeSnapshotProfile.FPS)!=0||result.sampleRate()!=NativeSnapshotProfile.SAMPLE_RATE)throw broken("Native timing changed");
            frame=result.frame();return result;}
    }
    public byte[] saveState()throws IOException{
        synchronized(calls){var value=call(NativeStepProtocol.save(next()));if(!(value instanceof NativeStepProtocol.State result)||result.frame()!=frame)throw broken("Native save frame mismatch");return result.state();}
    }
    public void loadState(long internalFrame,byte[] bytes)throws IOException{
        if(internalFrame<NativeSnapshotProfile.BOOTSTRAP_FRAMES)throw new IOException("State predates fixed bootstrap");
        synchronized(calls){var value=call(NativeStepProtocol.load(next(),internalFrame,bytes));if(!(value instanceof NativeStepProtocol.Loaded result)||result.frame()!=internalFrame)throw broken("Native restore frame mismatch");frame=internalFrame;}
    }
    private long next()throws IOException{checkOpen();if(request==Long.MAX_VALUE)throw broken("Native request sequence exhausted");return request+1;}
    private NativeStepProtocol.Reply call(NativeStepProtocol.Request command)throws IOException{
        try{checkOpen();request=command.id();begin();NativeStepProtocol.writeRequest(out,command);out.flush();
            var response=NativeStepProtocol.readReply(in,command.id());if(response instanceof NativeStepProtocol.Failure failure)throw new IOException(failure.message());checkOpen();return response;
        }catch(IOException|RuntimeException failure){error=failure.toString();close();throw failure;}finally{end();}
    }
    private void checkOpen()throws IOException{if(closed.get())throw new IOException("Native snapshot session cancelled"+(error==null?"":": "+error));}
    private IOException broken(String message){error=message;close();return new IOException(message);}
    private void begin(){synchronized(lifecycle){deadline=System.nanoTime()+TIMEOUT;}}
    private void end(){synchronized(lifecycle){deadline=0;}}
    private void watch(){while(!closed.get()){
        Process exact=child;if(exact==null)return;long limit=deadline;
        if(limit!=0&&System.nanoTime()>=limit)synchronized(lifecycle){if(deadline==limit){error="Native snapshot command exceeded 15 seconds";close();return;}}
        if(!exact.isAlive()){error="Native snapshot child exited";close();return;}
        try{Thread.sleep(25);}catch(InterruptedException ex){Thread.currentThread().interrupt();return;}
    }}
    /** Nonblocking from any thread; never calls retro_* or waits for the emulation worker. */
    @Override public void close(){
        // Never wait for the spawn/deadline monitor on the client thread. If cancellation
        // saw child == null, the constructor checks closed immediately after publication.
        closed.set(true);Process exact=child;if(exact!=null&&exact.isAlive())exact.destroyForcibly();
        if(!starting)reap();
    }
    private void reap(){
        if(!reaping.compareAndSet(false,true))return;Process exact=child;
        Runnable action=()->{
            boolean interrupted=false;
            if(exact!=null)for(;;)try{exact.waitFor();break;}catch(InterruptedException ex){interrupted=true;exact.destroyForcibly();}
            try{if(workspace!=null)workspace.close();}catch(IOException failure){if(error==null)error="Snapshot temporary cleanup refused: "+failure.getMessage();}
            finally{NativeProcessSession.releaseStepSlot(exact);if(interrupted)Thread.currentThread().interrupt();}
        };
        // No native child exists on pre-launch failures; still keep directory I/O off the cancellation thread.
        daemon("PIQ snapshot exact reap",action);
    }
    private void drain(InputStream source){try(var stream=source){byte[] bytes=new byte[2048];for(int n;(n=stream.read(bytes))!=-1;)synchronized(diagnostics){diagnostics.append(new String(bytes,0,n,java.nio.charset.StandardCharsets.UTF_8));if(diagnostics.length()>16384)diagnostics.delete(0,diagnostics.length()-16384);}}catch(IOException ignored){}}
    private static void daemon(String name,Runnable task){Thread thread=new Thread(task,name);thread.setDaemon(true);thread.start();}
}
