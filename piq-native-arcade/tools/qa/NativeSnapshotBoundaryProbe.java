package cn.piq.nativearcade.qa;

import cn.piq.nativearcade.NativeSnapshotProfile;
import cn.piq.nativearcade.client.NativeSnapshotCore;
import cn.piq.nativearcade.bridge.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Two separate MC-parent substitutes using the actual production adapter and frozen real core. */
public final class NativeSnapshotBoundaryProbe {
    static long assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static String json(Object value){
        if(value instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(NativeSnapshotBoundaryProbe::json).toList())+"]";
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\"";
        return String.valueOf(value);
    }
    static void child(Path runtime,Path rom)throws Exception{
        origin(NativeSnapshotProfile.class,"PIQ_QA_NATIVE_ORIGIN");origin(NativeSnapshotCore.class,"PIQ_QA_NATIVE_ORIGIN");
        origin(NativeSnapshotSession.class,"PIQ_QA_NATIVE_ORIGIN");origin(NativeSnapshotState.class,"PIQ_QA_NATIVE_ORIGIN");
        origin(NativeProcessSession.class,"PIQ_QA_NATIVE_ORIGIN");origin(NativeStepProtocol.class,"PIQ_QA_NATIVE_ORIGIN");
        origin(Class.forName("cn.piq.nativearcade.bridge.NativeSnapshotWorkspace"),"PIQ_QA_NATIVE_ORIGIN");
        origin(cn.piq.fcarcade.cabinet.CabinetSyncCore.class,"PIQ_QA_FC_ORIGIN");origin(cn.piq.fcarcade.cabinet.CabinetFrame.class,"PIQ_QA_FC_ORIGIN");
        try(var core=new NativeSnapshotCore(runtime,rom,ignored->{})){
            var out=new DataOutputStream(new BufferedOutputStream(System.out,65536));
            var in=new DataInputStream(new BufferedInputStream(System.in,65536));
            out.writeLong(core.ownedPid());NativeStepProtocol.writeHello(out,new NativeStepProtocol.Hello(core.targetFps(),48000,4));out.flush();
            if(core.maxPlayers()!=2||core.snapshotIntervalFrames()!=1800||!core.compatibilityId().equals(NativeSnapshotProfile.COMPATIBILITY_ID))throw new AssertionError("Actual core capabilities");
            byte[] unchanged=core.saveState();
            for(long wrong:new long[]{-1,1,Long.MAX_VALUE}){
                try{core.loadState(unchanged,wrong);throw new AssertionError("Wrong logical frame accepted");}catch(IOException expected){}
                if(!Arrays.equals(unchanged,core.saveState()))throw new AssertionError("Rejected wrong frame mutated native state");
            }
            long frame=32;
            for(long id=1;;id++){
                var request=NativeStepProtocol.readRequest(in,id);NativeStepProtocol.Reply reply;
                switch(request.kind()){
                    case NativeStepProtocol.STEP->{var f=core.runFrame(request.p1(),request.p2(),request.p3(),request.p4());
                        reply=new NativeStepProtocol.Frame(id,++frame,core.targetFps(),48000,true,true,f.width(),f.height(),f.displayAspect(),f.rotation(),f.abgr(),f.pcm48k());}
                    case NativeStepProtocol.SAVE->reply=new NativeStepProtocol.State(id,frame,core.saveState());
                    case NativeStepProtocol.LOAD->{core.loadState(request.state(),request.frame());frame=NativeSnapshotState.decode(request.state(),NativeSnapshotProfile.ROMS.get(rom.getFileName().toString())).internalFrame();reply=new NativeStepProtocol.Loaded(id,frame);}
                    case NativeStepProtocol.CLOSE->{cancelWithSpawnMonitorHeld(core);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                        while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)Thread.sleep(10);
                        if(NativeProcessSession.hasLiveSession())throw new AssertionError("Exact child lease not reclaimed");
                        NativeStepProtocol.writeReply(out,new NativeStepProtocol.Closed(id));out.flush();return;}
                    default->throw new AssertionError();
                }
                NativeStepProtocol.writeReply(out,reply);out.flush();
            }
        }
    }
    static void origin(Class<?> type,String variable)throws Exception{
        Path expected=Path.of(Objects.requireNonNull(System.getenv(variable),variable)).toRealPath();
        if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))throw new AssertionError("Wrong actual production origin: "+type);
    }
    static void cancelWithSpawnMonitorHeld(NativeSnapshotCore core)throws Exception{
        var sessionField=NativeSnapshotCore.class.getDeclaredField("session");sessionField.setAccessible(true);Object session=sessionField.get(core);
        var lockField=NativeSnapshotSession.class.getDeclaredField("lifecycle");lockField.setAccessible(true);Object lock=lockField.get(session);
        var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);
        Thread holder=new Thread(()->{synchronized(lock){acquired.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}},"QA deliberately held spawn monitor");
        holder.setDaemon(true);holder.start();if(!acquired.await(2,TimeUnit.SECONDS))throw new AssertionError("QA lock not acquired");
        long began=System.nanoTime();try{core.requestClose();}finally{release.countDown();holder.join(3000);}
        if(System.nanoTime()-began>TimeUnit.MILLISECONDS.toNanos(500))throw new AssertionError("requestClose blocked on spawn monitor");
    }
    static final class Parent implements AutoCloseable{
        final Process process;final DataInputStream in;final DataOutputStream out;
        final ExecutorService reads=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"QA bounded response");t.setDaemon(true);return t;});
        long id,pid=-1;NativeStepProtocol.Hello hello;
        Parent(Path runtime,Path rom)throws Exception{
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),NativeSnapshotBoundaryProbe.class.getName(),"child",runtime.toString(),rom.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            in=new DataInputStream(new BufferedInputStream(process.getInputStream(),65536));out=new DataOutputStream(new BufferedOutputStream(process.getOutputStream(),65536));
            try{bounded(()->{pid=in.readLong();hello=NativeStepProtocol.readHello(in);return null;});}catch(Exception ex){close();throw ex;}
        }
        <T>T bounded(Callable<T> action)throws Exception{
            Future<T> task=reads.submit(action);try{return task.get(25,TimeUnit.SECONDS);}catch(ExecutionException ex){throw new IOException(ex.getCause());}catch(TimeoutException ex){task.cancel(true);throw ex;}
        }
        NativeStepProtocol.Reply call(NativeStepProtocol.Request request)throws Exception{
            id=request.id();return bounded(()->{NativeStepProtocol.writeRequest(out,request);out.flush();return NativeStepProtocol.readReply(in,id);});
        }
        byte[] save()throws Exception{return ((NativeStepProtocol.State)call(NativeStepProtocol.save(id+1))).state();}
        void load(byte[] state)throws Exception{check(call(NativeStepProtocol.load(id+1,0,state)) instanceof NativeStepProtocol.Loaded,"Actual load reply");}
        NativeStepProtocol.Frame step(int p1,int p2)throws Exception{return (NativeStepProtocol.Frame)call(NativeStepProtocol.step(id+1,p1,p2,0,0));}
        @Override public void close(){
            try{if(process.isAlive()){call(NativeStepProtocol.close(id+1));check(process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0,"QA wrapper orderly close");}}
            catch(Exception failure){throw new RuntimeException(failure);}
            finally{
                // Descendants belong only to this newly-created QA wrapper; no name-wide process operations.
                process.descendants().forEach(p->{if(p.isAlive())p.destroyForcibly();});
                if(process.isAlive())process.destroyForcibly();reads.shutdownNow();
            }
        }
    }
    static Map<String,Object> run(Path runtime,Path rom)throws Exception{
        long began=System.nanoTime();var result=new LinkedHashMap<String,Object>();
        long pidA,pidB,wrapperA,wrapperB;int video=0,audio=0,state=0;boolean initialMatch,restoreMatch;int bytes;
        try(var a=new Parent(runtime,rom)){
            byte[] initial=a.save();bytes=initial.length;pidA=a.pid;wrapperA=a.process.pid();
            check(NativeSnapshotState.decode(initial,NativeSnapshotProfile.ROMS.get(rom.getFileName().toString())).internalFrame()==32,"logical0 is exactly native32");
            Thread.sleep(3000);
            try(var b=new Parent(runtime,rom)){
                pidB=b.pid;wrapperB=b.process.pid();check(pidA!=pidB&&wrapperA!=wrapperB,"Independent actual parent and native processes");
                check(a.hello.equals(b.hello),"Actual timing equal");initialMatch=Arrays.equals(initial,b.save());
                b.load(initial);restoreMatch=Arrays.equals(initial,b.save());check(restoreMatch,"Bootstrap32 cold LOAD then immediate full SAVE equality");
                for(int frame=0;frame<600;frame++){
                    int p1=frame<4?1<<2:frame==12?1<<3:(frame%29<3?1:0)|(frame%67<9?1<<7:0);
                    int p2=frame<4?1<<2:frame==20?1<<3:(frame%37<4?1<<8:0)|(frame%53<7?1<<6:0);
                    var x=a.step(p1,p2);var y=b.step(p1,p2);
                    boolean v=x.frame()==y.frame()&&x.width()==y.width()&&x.height()==y.height()&&x.rotation()==y.rotation()&&Float.compare(x.displayAspect(),y.displayAspect())==0&&Arrays.equals(x.abgr(),y.abgr());
                    boolean p=Arrays.equals(x.pcm48k(),y.pcm48k());boolean s=Arrays.equals(a.save(),b.save());
                    assertions+=3;if(!v)video++;if(!p)audio++;if(!s)state++;
                }
                result.put("initial_A_sha256",NativeSnapshotProfile.hash(initial));
            }
        }
        check(ProcessHandle.of(pidA).map(p->!p.isAlive()).orElse(true)&&ProcessHandle.of(pidB).map(p->!p.isAlive()).orElse(true),"Both exact native children exited");
        result.put("ok",restoreMatch&&video==0&&audio==0&&state==0);result.put("game",rom.getFileName().toString());
        result.put("bootstrap_frames_each",32);result.put("logical_frame_zero",true);result.put("cold_start_delay_ms_at_least",3000);
        result.put("fresh_initial_equal",initialMatch);result.put("cold_restore_immediate_full_state_equal",restoreMatch);
        result.put("compared_frames",600);result.put("full_state_comparisons",600);result.put("video_mismatches",video);result.put("pcm_mismatches",audio);result.put("state_mismatches",state);
        result.put("envelope_bytes",bytes);result.put("native_state_bytes",bytes-NativeSnapshotState.HEADER_BYTES);
        result.put("wrong_logical_frame_rejected_without_native_mutation_each_parent",3);
        result.put("requestClose_while_spawn_monitor_held_nonblocking",true);result.put("production_code_sources_checked_each_parent",9);
        result.put("native_pids",List.of(pidA,pidB));result.put("parent_pids",List.of(wrapperA,wrapperB));result.put("all_owned_children_exited",true);
        result.put("elapsed_seconds",(System.nanoTime()-began)/1e9);return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length==3&&args[0].equals("child")){child(Path.of(args[1]),Path.of(args[2]));return;}
        if(args.length!=3)throw new IllegalArgumentException("runtime kof97 mslug2");
        var games=List.of(run(Path.of(args[0]),Path.of(args[1])),run(Path.of(args[0]),Path.of(args[2])));
        boolean ok=games.stream().allMatch(g->Boolean.TRUE.equals(g.get("ok")));
        System.out.println(json(Map.of("ok",ok,"real_core",true,"games",games,"assertions",assertions,"profile",NativeSnapshotProfile.COMPATIBILITY_ID,"state_normalization_or_ignored_bytes",false,"minecraft_started",false)));
        if(!ok)System.exit(2);
    }
}
