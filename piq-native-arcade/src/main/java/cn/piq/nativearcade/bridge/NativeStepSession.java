// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Request-driven native child; no JNA, Minecraft calls or wall-clock core advancement. */
public final class NativeStepSession implements AutoCloseable {
    public record RuntimeFiles(Path helper,String helperSha,Path core,String coreSha,Path jna,String jnaSha){
        public RuntimeFiles{
            Objects.requireNonNull(helper);Objects.requireNonNull(core);Objects.requireNonNull(jna);
            for(String sha:List.of(helperSha,coreSha,jnaSha))if(!sha.matches("[0-9A-Fa-f]{64}"))throw new IllegalArgumentException("Runtime SHA-256 required");
        }
    }
    private static final long COMMAND_NANOS=TimeUnit.SECONDS.toNanos(15);
    private final AtomicBoolean closed=new AtomicBoolean(),cleaned=new AtomicBoolean();
    private final Object calls=new Object(),lifecycle=new Object();
    private final StringBuilder log=new StringBuilder();
    private final RuntimeFiles runtime;
    private volatile Process child;
    private volatile long deadline;
    private volatile String error;
    private Path owned;
    private DataInputStream input;private DataOutputStream output;
    private long request,frame;
    private NativeStepProtocol.Hello hello;

    /** Runtime identities come from a fixed adapter, never a network packet. */
    public NativeStepSession(RuntimeFiles runtime,Path rom)throws IOException{
        if(!System.getProperty("os.name","").startsWith("Windows")||!System.getProperty("os.arch","").matches("amd64|x86_64"))
            throw new IOException("Synchronous native arcade requires Windows x64");
        this.runtime=Objects.requireNonNull(runtime);
        NativeProcessSession.acquireStepSlot();
        try{
            verify(runtime.helper,runtime.helperSha);verify(runtime.core,runtime.coreSha);verify(runtime.jna,runtime.jnaSha);
            String name=rom.getFileName().toString();
            if(!name.matches("[a-z0-9_]{1,32}\\.zip"))throw new IOException("Exact lower-case arcade driver ZIP name required");
            owned=Files.createTempDirectory("piq-native-step-owned-");
            Path staged=NativeRomStaging.stage(rom,name.substring(0,name.length()-4),owned);
            String cp=runtime.helper.toAbsolutePath()+File.pathSeparator+runtime.jna.toAbsolutePath();
            synchronized(lifecycle){
                child=NativeProcessSession.launchStepChild(new ProcessBuilder(
                    Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx256m","-Djna.nosys=true","-cp",cp,
                    "cn.piq.nativearcade.bridge.NativeStepWorker",runtime.core.toAbsolutePath().toString(),staged.toString()).directory(owned.toFile()));
                input=new DataInputStream(new BufferedInputStream(child.getInputStream(),65536));
                output=new DataOutputStream(new BufferedOutputStream(child.getOutputStream(),65536));
            }
            Process exact=child;
            daemon("PIQ native step log",()->drain(exact.getErrorStream()));
            startDeadline();
            daemon("PIQ native step deadline",this::watch);
            hello=NativeStepProtocol.readHello(input);endDeadline();
            if(!child.isAlive())throw new IOException("Native step helper exited during hello");
        }catch(Throwable failure){
            error=failure.toString();close();
            if(failure instanceof Error fatal)throw fatal;
            if(failure instanceof IOException io)throw io;
            throw new IOException("Native step startup failed",failure);
        }
    }
    public NativeStepProtocol.Hello hello(){return hello;}
    public long frame(){return frame;}
    public String error(){return error;}
    public String diagnostics(){synchronized(log){return log.toString();}}
    public boolean isClosed(){return closed.get();}
    public long ownedPid(){Process exact=child;return exact==null?-1:exact.pid();}

    public NativeStepProtocol.Frame step(int p1,int p2,int p3,int p4)throws IOException{
        synchronized(calls){
            var reply=call(NativeStepProtocol.step(next(),p1,p2,p3,p4));
            if(!(reply instanceof NativeStepProtocol.Frame result)||result.frame()!=frame+1)throw fail("Step response/frame mismatch");
            frame=result.frame();return result;
        }
    }
    public byte[] saveState()throws IOException{
        synchronized(calls){
            var reply=call(NativeStepProtocol.save(next()));
            if(!(reply instanceof NativeStepProtocol.State result)||result.frame()!=frame)throw fail("Save response/frame mismatch");
            return result.state();
        }
    }
    public void loadState(long target,byte[] state)throws IOException{
        synchronized(calls){
            var reply=call(NativeStepProtocol.load(next(),target,state));
            if(!(reply instanceof NativeStepProtocol.Loaded result)||result.frame()!=target)throw fail("Load response/frame mismatch");
            frame=target;
        }
    }
    private long next()throws IOException{
        if(closed.get()||error!=null||request==Long.MAX_VALUE)throw new IOException("Native step session closed"+(error==null?"":": "+error));
        return request+1;
    }
    private NativeStepProtocol.Reply call(NativeStepProtocol.Request command)throws IOException{
        try{
            if(closed.get())throw new IOException("Native step session closed");
            request=command.id(); // Only consume the ID after the public arguments have been validated.
            startDeadline();
            NativeStepProtocol.writeCommand(output,command);output.flush();
            var result=NativeStepProtocol.readReply(input,command.id());
            if(result instanceof NativeStepProtocol.Failure failure)throw new IOException("Native step: "+failure.message());
            if(closed.get())throw new IOException("Native step session was canceled");
            return result;
        }catch(IOException|RuntimeException ex){error=ex.toString();close();throw ex;}
        finally{endDeadline();}
    }
    private void startDeadline(){synchronized(lifecycle){deadline=System.nanoTime()+COMMAND_NANOS;}}
    private void endDeadline(){synchronized(lifecycle){deadline=0;}}
    private IOException fail(String reason){error=reason;close();return new IOException(reason);}
    private void watch(){
        while(!closed.get()){
            Process exact=child;if(exact==null)return;
            long expires=deadline;
            if(expires!=0&&System.nanoTime()>=expires){synchronized(lifecycle){
                // Do not apply an old command's expiry to a completed command or its successor.
                if(deadline==expires){error="Native step command exceeded 15 seconds";close();return;}
            }}
            if(!exact.isAlive()){error="Native step child exited";close();return;}
            try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        }
    }
    /** Safe from a cancellation thread even if the worker is blocked in native/pipe I/O. */
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        Process exact;
        synchronized(lifecycle){exact=child;if(exact!=null&&exact.isAlive())exact.destroyForcibly();}
        // Only the exact owned child is reaped. The shared slot is not released while it lives.
        if(exact==null){cleanup(null);return;}
        daemon("PIQ native step reap",()->{
            boolean interrupted=false;
            for(;;)try{exact.waitFor();break;}catch(InterruptedException ex){interrupted=true;exact.destroyForcibly();}
            cleanup(exact);if(interrupted)Thread.currentThread().interrupt();
        });
    }
    private void cleanup(Process exact){
        if(!cleaned.compareAndSet(false,true))return;
        try{
            // Fresh owned directory only; walking without FOLLOW_LINKS never descends junctions/symlinks.
            Path directory=owned;
            if(directory!=null){
                try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())try{Files.deleteIfExists(path);}catch(IOException ignored){}}
                catch(IOException ignored){}
            }
        }finally{NativeProcessSession.releaseStepSlot(exact);}
    }
    private void drain(InputStream stream){
        try(InputStream in=stream){byte[] buffer=new byte[2048];for(int count;(count=in.read(buffer))!=-1;){
            synchronized(log){log.append(new String(buffer,0,count,java.nio.charset.StandardCharsets.UTF_8));if(log.length()>16384)log.delete(0,log.length()-16384);}
        }}catch(IOException ignored){}
    }
    private static void verify(Path file,String expected)throws IOException{
        Path path=file.toAbsolutePath().normalize();
        for(Path p=path;p!=null;p=p.getParent()){
            var a=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()||(p.equals(path)?!a.isRegularFile():!a.isDirectory()))throw new IOException("Runtime path must be a regular non-link file");
        }
        try{
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[131072];for(int count;(count=in.read(buffer))!=-1;)sha.update(buffer,0,count);}
            if(!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(expected))throw new IOException("Runtime SHA mismatch: "+path.getFileName());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void daemon(String name,Runnable action){Thread thread=new Thread(action,name);thread.setDaemon(true);thread.start();}
}
