package cn.piq.gba.bridge;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Own diagnostic ROM/save and exact children only. Controlled launcher modes are labelled separately. */
public final class GbaShutdownRegression {
    static int assertions;static long pid=-1,closeNanos,shutdownNanos,repeatNanos;
    static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);check(condition.getAsBoolean(),"Timed out waiting for lifecycle condition");}
    static Object field(String name,Object object)throws Exception{Field f=GbaProcessSession.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static ClassNode read(Path path,String name)throws Exception{
        byte[] bytes;if(Files.isDirectory(path))bytes=Files.readAllBytes(path.resolve(name+".class"));else try(var zip=new ZipFile(path.toFile())){bytes=zip.getInputStream(zip.getEntry(name+".class")).readAllBytes();}
        var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;
    }
    static void wiring(Path path)throws Exception{
        String bridge="cn/piq/gba/bridge/GbaProcessSession";
        var node=read(path,"cn/piq/gba/client/GbaHandheldClient");
        var stop=node.methods.stream().filter(m->m.name.equals("stop")).findFirst().orElseThrow();int close=-1,input=-1;
        for(var i:stop.instructions)if(i instanceof MethodInsnNode m){if((m.owner.equals(bridge)||m.owner.equals("cn/piq/gba/bridge/GbaSession"))&&m.name.equals("close"))close=stop.instructions.indexOf(m);if(m.owner.endsWith("/KeyboardInput")&&m.name.equals("release"))input=stop.instructions.indexOf(m);}
        check(close>=0&&input>close,"Ordinary stop schedules save before optional input cleanup");
        var shutdown=node.methods.stream().filter(m->m.name.equals("shutdown")).findFirst().orElseThrow();
        check(shutdown.tryCatchBlocks.stream().anyMatch(t->t.type==null),"Application shutdown retains finally cleanup");
        long shutdownCalls=0;for(var i:shutdown.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals(bridge)&&m.name.equals("shutdown"))shutdownCalls++;
        check(shutdownCalls>=2,"Normal and exceptional GUI shutdown both reach bounded bridge shutdown");
        long nativeShutdownCalls=0;for(var i:shutdown.instructions)if(i instanceof MethodInsnNode m&&m.owner.equals("cn/piq/gba/bridge/GbaJniSession")&&m.name.equals("shutdown"))nativeShutdownCalls++;
        check(nativeShutdownCalls>=2,"Normal and exceptional GUI shutdown both reach native owner shutdown");
        var clinit=read(path,bridge).methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        check(java.util.Arrays.stream(clinit.instructions.toArray()).anyMatch(i->i instanceof MethodInsnNode m&&m.owner.equals("java/lang/Runtime")&&m.name.equals("addShutdownHook")),"JVM hook is installed even for cabinet use without handheld");
    }
    static void actual(Path runtime,Path rom,Path saves,String sha,String mode)throws Exception{
        var s=new GbaProcessSession(runtime,rom,saves,sha);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);int acknowledged=0;s.offerInput(257);
        while(acknowledged<2&&System.nanoTime()<deadline){check(s.error()==null,"Actual helper error: "+s.error());var frame=s.pollFrame();if(frame!=null&&frame.abgr()[0]==0xff000018)acknowledged++;Thread.sleep(5);}
        check(acknowledged>=2,"Original ARM diagnostic acknowledges A+B and writes SRAM[0]=3");
        pid=((Process)field("process",s)).pid();check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Exact real helper alive before normal exit");
        if(!mode.equals("hook-only")){long t=System.nanoTime();s.close();closeNanos=System.nanoTime()-t;check(closeNanos<TimeUnit.MILLISECONDS.toNanos(500),"Ordinary close remains nonblocking");}
        if(mode.equals("await")){check(s.awaitClosed(6000),"Normal close saved and reaped");check(!GbaProcessSession.active()&&s.error()==null,"Normal close releases ACTIVE without save error");check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Exact helper dead after await");}
    }
    static void spawn(Path runtime,Path rom,Path saves,String sha,boolean shutdown)throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var child=new AtomicReference<Process>();var cwd=new AtomicReference<Path>();
        var s=new GbaProcessSession(runtime,rom,saves,sha,b->{cwd.set(b.directory().toPath());entered.countDown();try{if(!go.await(3,TimeUnit.SECONDS))throw new IOException("Probe launch timeout");}catch(InterruptedException e){throw new IOException(e);}var p=b.start();child.set(p);pid=p.pid();return p;});
        Thread stopper=null;
        try{check(entered.await(2,TimeUnit.SECONDS),"Owned session published before delayed spawn");
            if(shutdown){stopper=new Thread(()->{long t=System.nanoTime();GbaProcessSession.shutdown();shutdownNanos=System.nanoTime()-t;});stopper.start();
                await(()->{try{return(Boolean)field("shuttingDown",null);}catch(Exception e){throw new RuntimeException(e);}});
                check(child.get()==null,"Shutdown acquires publication lock while ProcessBuilder has not returned");
            }else{long t=System.nanoTime();s.close();closeNanos=System.nanoTime()-t;check(closeNanos<TimeUnit.MILLISECONDS.toNanos(500),"Close does not wait for spawn");
                try{new GbaProcessSession(runtime,rom,saves,sha);throw new AssertionError("Slot released while spawn pending");}catch(IOException expected){check(GbaProcessSession.active(),"Pending child keeps exclusive slot");}}
            go.countDown();check(s.awaitClosed(6000),"Late child is reaped");check(child.get()!=null&&!child.get().isAlive(),"Only the late exact child was killed");check(!Files.exists(cwd.get()),"Late child private runtime removed only after death");check(!GbaProcessSession.active(),"Slot released after late child death");
            if(stopper!=null){stopper.join(6500);check(!stopper.isAlive()&&shutdownNanos<TimeUnit.SECONDS.toNanos(6),"Concurrent shutdown is bounded");}
        }finally{go.countDown();s.close();s.awaitClosed(6000);if(stopper!=null)stopper.join(6500);}
    }
    static final class StalledProcess extends Process {
        final CountDownLatch dead=new CountDownLatch(1);volatile boolean mayDie;final AtomicInteger kills=new AtomicInteger();
        final InputStream input=new InputStream(){public int read()throws IOException{try{dead.await();return -1;}catch(InterruptedException e){throw new IOException(e);}}};
        public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}public InputStream getInputStream(){return input;}public InputStream getErrorStream(){return InputStream.nullInputStream();}
        public int waitFor()throws InterruptedException{dead.await();return 1;}public boolean waitFor(long n,TimeUnit u)throws InterruptedException{return dead.await(n,u);}
        public int exitValue(){if(isAlive())throw new IllegalThreadStateException();return 1;}public boolean isAlive(){return dead.getCount()!=0;}
        public void destroy(){destroyForcibly();}public Process destroyForcibly(){kills.incrementAndGet();if(mayDie)dead.countDown();return this;}
    }
    static void timeout(Path runtime,Path rom,Path saves,String sha)throws Exception{
        var fake=new StalledProcess();var entered=new CountDownLatch(1);var s=new GbaProcessSession(runtime,rom,saves,sha,b->{entered.countDown();return fake;});
        try{check(entered.await(2,TimeUnit.SECONDS),"Controlled stalled process installed");long t=System.nanoTime();GbaProcessSession.shutdown();shutdownNanos=System.nanoTime()-t;
            check(shutdownNanos>=TimeUnit.SECONDS.toNanos(5)&&shutdownNanos<TimeUnit.MILLISECONDS.toNanos(6700),"Shared shutdown total budget at most six seconds plus scheduler tolerance");
            check(fake.kills.get()>0&&GbaProcessSession.active()&&!s.awaitClosed(0),"Unreaped child cannot release slot or claim finished");
            t=System.nanoTime();GbaProcessSession.shutdown();repeatNanos=System.nanoTime()-t;check(repeatNanos<TimeUnit.MILLISECONDS.toNanos(500),"Second shutdown/hook cannot restart six-second deadline");
        }finally{fake.mayDie=true;fake.destroyForcibly();check(s.awaitClosed(3000),"Controlled stalled child finally reaped");}
        check(!GbaProcessSession.active(),"Finished stalled child releases ACTIVE");
    }
    static void failed(Path runtime,Path rom,Path saves,String sha)throws Exception{
        try{new GbaProcessSession(null,rom,saves,sha);throw new AssertionError("Null runtime accepted");}catch(NullPointerException expected){check(!GbaProcessSession.active(),"Invalid constructor cannot claim slot");}
        var cwd=new AtomicReference<Path>();var s=new GbaProcessSession(runtime,rom,saves,sha,b->{cwd.set(b.directory().toPath());throw new IOException("Controlled process creation failure");});
        check(s.awaitClosed(4000)&&s.error()!=null,"Spawn exception closes lifecycle");check(!GbaProcessSession.active()&&!Files.exists(cwd.get()),"Exception removes private runtime and slot");
        var next=new GbaProcessSession(runtime,rom,saves,sha);next.close();check(next.awaitClosed(4000)&&!GbaProcessSession.active(),"A new independent session can close after failure");
    }
    public static void main(String[] args)throws Exception{
        Path production=Path.of(args[0]),runtime=Path.of(args[2]),rom=Path.of(args[3]),saves=Path.of(args[4]);String mode=args[1],sha=args[5];
        check(Path.of(GbaProcessSession.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(production.toRealPath()),"Exact tested production CodeSource");
        switch(mode){
            case "immediate","hook-only","await"->actual(runtime,rom,saves,sha,mode);
            case "inactive"->{long t=System.nanoTime();GbaProcessSession.shutdown();shutdownNanos=System.nanoTime()-t;check(shutdownNanos<TimeUnit.MILLISECONDS.toNanos(500)&&!GbaProcessSession.active(),"Inactive shutdown returns without worker/native startup");wiring(production);}
            case "spawn-close"->spawn(runtime,rom,saves,sha,false);
            case "spawn-shutdown"->spawn(runtime,rom,saves,sha,true);
            case "timeout"->timeout(runtime,rom,saves,sha);
            case "constructor-failure"->failed(runtime,rom,saves,sha);
            default->throw new IllegalArgumentException(mode);
        }
        System.out.println("{\"ok\":true,\"mode\":\""+mode+"\",\"assertions\":"+assertions+",\"owned_pid\":"+pid+",\"close_ns\":"+closeNanos+",\"shutdown_ns\":"+shutdownNanos+",\"repeat_shutdown_ns\":"+repeatNanos+"}");System.out.flush();
        System.exit(0); // Normal JVM exit, not halt or force kill: the production hook must save SRAM.
    }
}
