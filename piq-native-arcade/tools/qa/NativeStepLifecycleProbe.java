package cn.piq.nativearcade.bridge;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Runs the actual parent implementation against an explicitly identified fake child JAR. */
public final class NativeStepLifecycleProbe {
    private static int assertions;
    private static Path scratch,origin;
    private static NativeStepSession.RuntimeFiles runtime;
    private static Process sentinel;
    private static final Set<ProcessHandle> observedChildren=new HashSet<>();
    public static void main(String[] args)throws Exception{
        if(args.length==1&&args[0].equals("sentinel")){for(;;)Thread.sleep(60000);}
        String mode=args[0];origin=Path.of(args[1]).toRealPath();scratch=Path.of(args[2]).toRealPath();
        runtime=new NativeStepSession.RuntimeFiles(Path.of(args[3]),args[4],Path.of(args[5]),args[6],Path.of(args[7]),args[8]);
        for(Class<?> type:List.of(NativeStepSession.class,NativeProcessSession.class,NativeStepProtocol.class,NativeRomStaging.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(origin),"Actual parent CodeSource: "+type);
        sentinel=new ProcessBuilder(java(),"-cp",System.getProperty("java.class.path"),NativeStepLifecycleProbe.class.getName(),"sentinel").start();
        long began=System.nanoTime();
        try{
            check(sentinel.isAlive(),"Unrelated sentinel launched");
            switch(mode){
                case "normal"->normal();
                case "badhash"->badHash();
                case "nohello"->noHello();
                case "cancel"->blocked(false);
                case "deadline"->blocked(true);
                case "idle"->idle();
                case "badid","badframe","eof"->badReply(mode);
                default->throw new AssertionError("Unknown probe "+mode);
            }
            check(!NativeProcessSession.hasLiveSession(),"Final actual shared slot clear");
            check(sentinel.isAlive(),"Unrelated sentinel survived all parent cleanup");
            for(ProcessHandle child:observedChildren)check(!child.isAlive(),"Observed exact child reaped "+child.pid());
            System.out.println("{\"ok\":true,\"case\":\""+mode+"\",\"assertions\":"+assertions+",\"elapsed_ms\":"+(System.nanoTime()-began)/1000000
                +",\"actual_parent\":true,\"fake_child\":true,\"native_core_loaded\":false,\"minecraft_started\":false}");
        }finally{
            // QA owns these exact handles; never kill by name or search outside this JVM's children.
            for(ProcessHandle child:ProcessHandle.current().children().toList())if(child.pid()!=sentinel.pid()){
                child.destroyForcibly();try{child.onExit().get(5,TimeUnit.SECONDS);}catch(Exception ignored){}
            }
            sentinel.destroyForcibly();sentinel.waitFor(5,TimeUnit.SECONDS);
        }
    }
    private static void normal()throws Exception{
        NativeStepSession session=open("normal");long pid=session.ownedPid();Path owned=owned(session);
        check(session.hello().maxPorts()==4&&session.frame()==0,"Hello does not advance frame");
        check(session.saveState()[0]==0,"State before first step is frame zero");
        for(int port=0;port<4;port++)for(int bit=0;bit<12;bit++){
            int[] masks=new int[4];masks[port]=1<<bit;var frame=session.step(masks[0],masks[1],masks[2],masks[3]);
            check(frame.abgr()[0]==(0xff000000|1<<bit),"Four-port exact IPC bit "+port+":"+bit);
            check(frame.pcm48k()[0]==frame.frame()&&frame.pcm48k().length==2,"Per-step PCM indexed once");
        }
        long before=session.frame();
        rejects(IllegalArgumentException.class,()->session.step(-1,0,0,0));
        rejects(IllegalArgumentException.class,()->session.loadState(-1,new byte[]{1}));
        rejects(IllegalArgumentException.class,()->session.loadState(0,new byte[0]));
        check(session.step(0,0,0,0).frame()==before+1,"Rejected local args do not consume request IDs");
        session.loadState(9,new byte[]{9,42});check(session.frame()==9,"Load restores explicit frame");
        check(session.step(0,0,0,0).frame()==10,"Exact next step after restore");
        rejects(IOException.class,()->open("normal"));check(alive(pid)&&!session.isClosed(),"Rejected second session cannot close current owner");
        session.close();session.close();settled(pid,owned);
        rejects(IOException.class,()->session.step(0,0,0,0));
        reopen();
    }
    private static void badHash()throws Exception{
        for(int which=0;which<3;which++){
            var wrong=new NativeStepSession.RuntimeFiles(runtime.helper(),which==0?"0".repeat(64):runtime.helperSha(),runtime.core(),which==1?"0".repeat(64):runtime.coreSha(),runtime.jna(),which==2?"0".repeat(64):runtime.jnaSha());
            rejects(IOException.class,()->new NativeStepSession(wrong,rom("normal")));
            check(!NativeProcessSession.hasLiveSession(),"Hash failure releases unlaunched slot");
            check(ProcessHandle.current().children().filter(p->p.pid()!=sentinel.pid()).findAny().isEmpty(),"Hash failure never launches child");
        }
        rejects(IOException.class,()->new NativeStepSession(runtime,null));
        check(!NativeProcessSession.hasLiveSession(),"Null ROM after CAS releases slot");
        reopen();
    }
    private static void noHello()throws Exception{
        FutureTask<Throwable> opening=new FutureTask<>(()->{try{open("nohello");return null;}catch(Throwable ex){return ex;}});
        Thread thread=new Thread(opening,"QA blocked constructor");thread.setDaemon(true);thread.start();
        await(()->ProcessHandle.current().children().anyMatch(p->p.pid()!=sentinel.pid()),5000,"Actual no-hello child started");
        ProcessHandle child=ProcessHandle.current().children().filter(p->p.pid()!=sentinel.pid()).findFirst().orElseThrow();observedChildren.add(child);
        long began=System.nanoTime();Throwable error=opening.get(22,TimeUnit.SECONDS);
        check(error instanceof IOException,"No-hello exits as IOException");
        check((System.nanoTime()-began)>TimeUnit.SECONDS.toNanos(13),"No-hello uses real 15-second deadline");
        settled(child.pid(),null);reopen();
    }
    private static void blocked(boolean timeout)throws Exception{
        NativeStepSession session=open("blocked");long pid=session.ownedPid();Path owned=owned(session);
        long began=System.nanoTime();FutureTask<Throwable> calling=new FutureTask<>(()->{try{session.step(1,0,0,0);return null;}catch(Throwable ex){return ex;}});
        Thread thread=new Thread(calling,"QA blocked command");thread.setDaemon(true);thread.start();
        await(()->session.diagnostics().contains("QA_STEP_BLOCKED"),5000,"Fake child received blocked command");
        if(!timeout){long closeStart=System.nanoTime();session.close();check(System.nanoTime()-closeStart<TimeUnit.SECONDS.toNanos(1),"Cancellation close is nonblocking");}
        Throwable error=calling.get(timeout?22:5,TimeUnit.SECONDS);
        check(error instanceof IOException,"Blocked pipe unblocks with IOException");
        if(timeout){check(System.nanoTime()-began>TimeUnit.SECONDS.toNanos(14),"Command uses real 15-second deadline");check(session.error()!=null,"Deadline reported");}
        settled(pid,owned);reopen();
    }
    private static void idle()throws Exception{
        NativeStepSession session=open("normal");long pid=session.ownedPid();Path owned=owned(session);
        Thread.sleep(16250);
        check(!session.isClosed()&&alive(pid),"Idle beyond command timeout remains live");
        check(session.frame()==0&&session.step(0,0,0,0).frame()==1,"Idle never advances core frame");
        session.close();settled(pid,owned);reopen();
    }
    private static void badReply(String mode)throws Exception{
        NativeStepSession session=open(mode);long pid=session.ownedPid();Path owned=owned(session);
        rejects(IOException.class,()->session.step(0,0,0,0));check(session.isClosed(),"Malformed/EOF reply fails closed");
        settled(pid,owned);reopen();
    }
    private static NativeStepSession open(String name)throws Exception{
        NativeStepSession value=new NativeStepSession(runtime,rom(name));
        ProcessHandle handle=ProcessHandle.of(value.ownedPid()).orElseThrow();observedChildren.add(handle);
        check(NativeProcessSession.hasLiveSession()&&handle.isAlive(),"Actual child owns shared native slot");return value;
    }
    private static void reopen()throws Exception{
        NativeStepSession next=open("normal");long pid=next.ownedPid();Path owned=owned(next);
        check(next.step(0,0,0,0).frame()==1,"New owner can step after previous exact child exit");next.close();settled(pid,owned);
    }
    private static void settled(long pid,Path owned)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until){Thread.sleep(5);}
        check(!NativeProcessSession.hasLiveSession(),"Shared slot released within bounded reap");
        check(!alive(pid),"Shared slot never released while exact child lives");
        if(owned!=null)check(!Files.exists(owned,LinkOption.NOFOLLOW_LINKS),"Only owned staging directory cleaned");
        check(sentinel.isAlive(),"Unrelated sentinel remains alive after reap");
    }
    private static Path owned(NativeStepSession session)throws Exception{Field f=NativeStepSession.class.getDeclaredField("owned");f.setAccessible(true);return(Path)f.get(session);}
    private static Path rom(String name)throws IOException{
        Path file=scratch.resolve(name+".zip");
        if(!Files.exists(file)){byte[] emptyZip=new byte[22];emptyZip[0]=0x50;emptyZip[1]=0x4b;emptyZip[2]=5;emptyZip[3]=6;Files.write(file,emptyZip,StandardOpenOption.CREATE_NEW);}
        return file;
    }
    private static boolean alive(long pid){return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);}
    private static String java(){return Path.of(System.getProperty("java.home"),"bin","java.exe").toString();}
    private interface Throwing{void run()throws Exception;}
    private static void rejects(Class<? extends Throwable> type,Throwing operation)throws Exception{
        try{operation.run();throw new AssertionError("Expected "+type);}catch(Throwable caught){if(!type.isInstance(caught))throw caught;assertions++;}
    }
    private static void await(BooleanSupplier condition,long millis,String what)throws Exception{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(10);check(condition.getAsBoolean(),what);
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
