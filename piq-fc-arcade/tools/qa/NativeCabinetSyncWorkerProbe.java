package cn.piq.fcarcade.qa;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.CabinetSyncWorker;
import cn.piq.nativearcade.NativeSnapshotProfile;
import cn.piq.nativearcade.bridge.*;
import cn.piq.nativearcade.client.NativeSnapshotCore;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Final-JAR-only worker/native integration. QA observes whole frames; no MC/network/UI is started. */
public final class NativeCabinetSyncWorkerProbe {
    static long assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static String json(Object value){
        if(value instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(NativeCabinetSyncWorkerProbe::json).toList())+"]";
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n")+"\"";
        return String.valueOf(value);
    }
    static void origin(Class<?> type,String variable)throws Exception{
        Path expected=Path.of(Objects.requireNonNull(System.getenv(variable),variable)).toRealPath();
        if(!Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected))throw new IllegalStateException("Wrong actual production origin: "+type);
    }
    static void origins()throws Exception{
        for(Class<?> type:new Class<?>[]{CabinetSyncWorker.class,CabinetSyncWorker.Factory.class,CabinetSyncCore.class,CabinetFrame.class,CabinetSyncTimeline.class,CabinetSyncState.class})origin(type,"PIQ_QA_FC_ORIGIN");
        for(Class<?> type:new Class<?>[]{NativeSnapshotProfile.class,NativeSnapshotCore.class,NativeSnapshotSession.class,NativeSnapshotState.class,NativeProcessSession.class,NativeStepProtocol.class})origin(type,"PIQ_QA_NATIVE_ORIGIN");
        origin(Class.forName("cn.piq.nativearcade.bridge.NativeSnapshotWorkspace"),"PIQ_QA_NATIVE_ORIGIN");
    }
    static String pixels(int[] values)throws Exception{
        var hash=MessageDigest.getInstance("SHA-256");for(int v:values){hash.update((byte)v);hash.update((byte)(v>>>8));hash.update((byte)(v>>>16));hash.update((byte)(v>>>24));}return HexFormat.of().formatHex(hash.digest());
    }
    static String pcm(short[] values)throws Exception{
        var hash=MessageDigest.getInstance("SHA-256");for(short v:values){hash.update((byte)v);hash.update((byte)(v>>>8));}return HexFormat.of().formatHex(hash.digest());
    }
    record Mark(long frame,int width,int height,float aspect,int rotation,int samples,String video,String audio){}
    static final class Node implements AutoCloseable {
        final AtomicReference<NativeSnapshotCore> actual=new AtomicReference<>();
        final AtomicReference<Runnable> cancel=new AtomicReference<>();
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicLong logical=new AtomicLong();final AtomicInteger loads=new AtomicInteger(),saves=new AtomicInteger();
        final CountDownLatch blocked=new CountDownLatch(1),resume=new CountDownLatch(1);
        volatile long blockAt=-1,pid=-1;volatile double fps;
        final List<Mark> marks=Collections.synchronizedList(new ArrayList<>());
        final Map<Long,String> digests=new TreeMap<>();final Map<Long,byte[]> snapshots=new TreeMap<>();final List<Long> acknowledgements=new ArrayList<>();
        CabinetSyncWorker.Event hello;final CabinetSyncWorker worker;
        Node(Path runtime,Path rom,boolean host)throws Exception{
            origins();String romHash=NativeSnapshotProfile.ROMS.get(rom.getFileName().toString());
            if(romHash==null)throw new IOException("Unapproved test ROM");
            worker=new CabinetSyncWorker(new CabinetSyncWorker.Factory(){
                public CabinetSyncWorker.Opened open()throws Exception{
                    if(cancelled.get())throw new IOException("Cancelled before opening");
                    var core=new NativeSnapshotCore(runtime,rom,closer->{cancel.set(closer);if(cancelled.get())closer.run();});actual.set(core);pid=core.ownedPid();fps=core.targetFps();
                    CabinetSyncCore observed=new CabinetSyncCore(){
                        public int maxPlayers(){return core.maxPlayers();}public double targetFps(){return core.targetFps();}public String compatibilityId(){return core.compatibilityId();}
                        public int snapshotIntervalFrames(){return core.snapshotIntervalFrames();}
                        public CabinetFrame runFrame(int a,int b,int c,int d)throws Exception{
                            if(logical.get()==blockAt){blocked.countDown();if(!resume.await(45,TimeUnit.SECONDS))throw new IOException("QA scheduling gate timed out");}
                            CabinetFrame f=core.runFrame(a,b,c,d);long frame=logical.incrementAndGet();
                            if(frame>1800){if(marks.size()>=1000)throw new IOException("QA observation bound");marks.add(new Mark(frame,f.width(),f.height(),f.displayAspect(),f.rotation(),f.pcm48k().length,pixels(f.abgr()),pcm(f.pcm48k())));}
                            return f;
                        }
                        public byte[] saveState()throws Exception{
                            byte[] state=core.saveState();saves.incrementAndGet();
                            // The production adapter validates this too on restore; observe without rewriting bytes.
                            if(java.nio.ByteBuffer.wrap(state).getLong(8)!=logical.get()+32)throw new IOException("Worker/native frame mismatch");return state;
                        }
                        public void loadState(byte[] state)throws Exception{throw new IOException("Worker must use authoritative-frame overload");}
                        public void loadState(byte[] state,long frame)throws Exception{core.loadState(state,frame);logical.set(frame);loads.incrementAndGet();}
                        public void requestClose(){resume.countDown();core.requestClose();}
                        public void close(){resume.countDown();core.close();}
                    };
                    return new CabinetSyncWorker.Opened(observed,romHash.toLowerCase(Locale.ROOT));
                }
                public void requestClose(){cancelled.set(true);resume.countDown();Runnable closer=cancel.get();if(closer!=null)closer.run();}
            },host);
            try{await(()->worker.isReady()&&hello!=null&&(!host||snapshots.containsKey(0L)));}
            catch(Exception|Error failure){close();throw failure;}
        }
        void pump(){
            String error=worker.error();if(error!=null)throw new IllegalStateException(error);
            for(CabinetSyncWorker.Event e;(e=worker.pollEvent())!=null;){
                switch(e.kind()){
                    case CabinetSyncWorker.Event.HELLO->hello=e;
                    case CabinetSyncWorker.Event.DIGEST->digests.put(e.frame(),e.hash());
                    case CabinetSyncWorker.Event.SNAPSHOT->{if(snapshots.size()>=3)throw new IllegalStateException("QA snapshot count bound");snapshots.put(e.frame(),e.state());}
                    case CabinetSyncWorker.Event.RESTORED->acknowledgements.add(e.frame());
                    default->throw new IllegalStateException("Unexpected actual worker event: "+e.kind());
                }
            }
        }
        void await(BooleanSupplier test)throws Exception{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            for(;;){pump();if(test.getAsBoolean())return;if(System.nanoTime()>=deadline)throw new IOException("Worker observation timed out at "+logical.get());Thread.sleep(2);}
        }
        void feed(long first,int count){
            if(first<1||count<1||count>3600)throw new IllegalArgumentException("QA frame command limit");
            for(int offset=0;offset<count;offset+=120){var batch=new ArrayList<CabinetSyncTimeline.Step>();
                for(int n=0;n<Math.min(120,count-offset);n++){long frame=first+offset+n;batch.add(new CabinetSyncTimeline.Step(frame,p1(frame),p2(frame),0,0));}
                if(!worker.frames(batch))throw new IllegalStateException("Actual worker rejected authoritative frame order");
            }
        }
        void waitFrame(long frame)throws Exception{await(()->logical.get()>=frame&&(frame%300!=0||digests.containsKey(frame)));}
        void restore(long frame,long goal,byte[] state,int count)throws Exception{
            blockAt=frame+1;worker.beginRestore(frame);feed(frame+1,count);worker.restore(UUID.randomUUID(),frame,goal,state,CabinetSyncState.hash(state));
        }
        void writeStatus(DataOutputStream out)throws Exception{
            pump();out.writeLong(logical.get());out.writeInt(loads.get());out.writeInt(saves.get());out.writeInt(acknowledgements.size());for(long frame:acknowledgements)out.writeLong(frame);
            out.writeInt(digests.size());for(var e:digests.entrySet()){out.writeLong(e.getKey());out.writeUTF(e.getValue());}
            out.writeInt(snapshots.size());for(long frame:snapshots.keySet())out.writeLong(frame);
            synchronized(marks){out.writeInt(marks.size());for(var m:marks){out.writeLong(m.frame);out.writeInt(m.width);out.writeInt(m.height);out.writeFloat(m.aspect);out.writeInt(m.rotation);out.writeInt(m.samples);out.writeUTF(m.video);out.writeUTF(m.audio);}}
        }
        @Override public void close(){
            worker.close();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(NativeProcessSession.hasLiveSession()&&System.nanoTime()<until)try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            if(NativeProcessSession.hasLiveSession()||pid>0&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new IllegalStateException("Owned native child or lease survived cancellation");
        }
    }
    static int p1(long n){return n<4?4:n==12?8:(n%29<3?1:0)|(n%67<9?128:0);}
    static int p2(long n){return n<4?4:n==20?8:(n%37<4?256:0)|(n%53<7?64:0);}
    static void child(Path runtime,Path rom,boolean host)throws Exception{
        try(var node=new Node(runtime,rom,host)){
            var out=new DataOutputStream(new BufferedOutputStream(System.out,65536));var in=new DataInputStream(new BufferedInputStream(System.in,65536));
            out.writeLong(node.pid);out.writeDouble(node.fps);out.writeUTF(node.hello.compatibility());out.writeUTF(new String(node.hello.state(),java.nio.charset.StandardCharsets.US_ASCII));out.flush();
            for(;;){
                int command=in.readUnsignedByte();
                switch(command){
                    case 'F'->{node.feed(in.readLong(),in.readInt());out.writeBoolean(true);}
                    case 'W'->{node.waitFrame(in.readLong());out.writeBoolean(true);}
                    case 'S'->{long frame=in.readLong();node.await(()->node.snapshots.containsKey(frame));byte[] state=node.snapshots.get(frame);out.writeInt(state.length);out.write(state);}
                    case 'R'->{long frame=in.readLong(),goal=in.readLong();int count=in.readInt(),length=in.readInt();if(length<1||length>CabinetSyncCore.MAX_STATE_BYTES)throw new IOException("QA snapshot bound");byte[] state=in.readNBytes(length);if(state.length!=length)throw new EOFException();node.restore(frame,goal,state,count);out.writeBoolean(true);}
                    case 'B'->{node.await(()->node.blocked.getCount()==0);node.writeStatus(out);}
                    case 'G'->{node.resume.countDown();out.writeBoolean(true);}
                    case 'I'->node.writeStatus(out);
                    case 'C'->{node.close();out.writeBoolean(true);out.flush();return;}
                    default->throw new IOException("Unknown QA command");
                }
                out.flush();
            }
        }
    }
    record Status(long frame,int loads,int saves,List<Long> acks,Map<Long,String> digests,List<Long> snapshots,List<Mark> marks){}
    static Status readStatus(DataInputStream in)throws Exception{
        long frame=in.readLong();int loads=in.readInt(),saves=in.readInt();var acks=new ArrayList<Long>();int n=count(in,10);for(int i=0;i<n;i++)acks.add(in.readLong());
        var digests=new TreeMap<Long,String>();n=count(in,20);for(int i=0;i<n;i++)digests.put(in.readLong(),in.readUTF());
        var snapshots=new ArrayList<Long>();n=count(in,3);for(int i=0;i<n;i++)snapshots.add(in.readLong());
        var marks=new ArrayList<Mark>();n=count(in,1000);for(int i=0;i<n;i++)marks.add(new Mark(in.readLong(),in.readInt(),in.readInt(),in.readFloat(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF()));
        return new Status(frame,loads,saves,acks,digests,snapshots,marks);
    }
    static int count(DataInputStream in,int maximum)throws IOException{int n=in.readInt();if(n<0||n>maximum)throw new IOException("QA response bound");return n;}
    interface Command<T>{T run(DataInputStream in,DataOutputStream out)throws Exception;}
    static final class Parent implements AutoCloseable {
        final Process process;final DataInputStream in;final DataOutputStream out;
        final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"QA worker pipe");t.setDaemon(true);return t;});
        long pid=-1;double fps;String compatibility,initialHash;boolean closed;
        Parent(Path runtime,Path rom,boolean host)throws Exception{
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx256m","-cp",System.getProperty("java.class.path"),NativeCabinetSyncWorkerProbe.class.getName(),"child",runtime.toString(),rom.toString(),Boolean.toString(host)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            in=new DataInputStream(new BufferedInputStream(process.getInputStream(),65536));out=new DataOutputStream(new BufferedOutputStream(process.getOutputStream(),65536));
            try{call((i,o)->{pid=i.readLong();fps=i.readDouble();compatibility=i.readUTF();initialHash=i.readUTF();return null;});}catch(Exception e){abort();throw e;}
        }
        <T>T call(Command<T> command)throws Exception{
            var pending=io.submit(()->command.run(in,out));try{return pending.get(100,TimeUnit.SECONDS);}catch(ExecutionException e){throw new IOException(e.getCause());}catch(TimeoutException e){pending.cancel(true);abort();throw e;}
        }
        void feed(long frame,int count)throws Exception{check(call((i,o)->{o.writeByte('F');o.writeLong(frame);o.writeInt(count);o.flush();return i.readBoolean();}),"Worker accepted frames");}
        void await(long frame)throws Exception{check(call((i,o)->{o.writeByte('W');o.writeLong(frame);o.flush();return i.readBoolean();}),"Worker reached logical frame "+frame);}
        byte[] snapshot(long frame)throws Exception{return call((i,o)->{o.writeByte('S');o.writeLong(frame);o.flush();int n=count(i,CabinetSyncCore.MAX_STATE_BYTES);if(n<1)throw new IOException("No snapshot");byte[] state=i.readNBytes(n);if(state.length!=n)throw new EOFException();return state;});}
        void restore(byte[] state,long frame,long goal,int count)throws Exception{check(call((i,o)->{o.writeByte('R');o.writeLong(frame);o.writeLong(goal);o.writeInt(count);o.writeInt(state.length);o.write(state);o.flush();return i.readBoolean();}),"Restore queued in actual worker");}
        Status status(boolean block)throws Exception{return call((i,o)->{o.writeByte(block?'B':'I');o.flush();return readStatus(i);});}
        void resume()throws Exception{check(call((i,o)->{o.writeByte('G');o.flush();return i.readBoolean();}),"QA scheduling gate released");}
        void abort(){
            // Only descendants of this exact, newly owned QA parent. Never select processes by name.
            process.descendants().forEach(p->{if(p.isAlive())p.destroyForcibly();});if(process.isAlive())process.destroyForcibly();io.shutdownNow();
        }
        @Override public void close(){
            if(closed)return;closed=true;
            try{if(process.isAlive()){check(call((i,o)->{o.writeByte('C');o.flush();return i.readBoolean();}),"Actual worker cancelled and reaped child");check(process.waitFor(8,TimeUnit.SECONDS)&&process.exitValue()==0,"Parent exited orderly");}
                check(pid<0||ProcessHandle.of(pid).map(p->!p.isAlive()).orElse(true),"Exact owned native PID exited");}
            catch(Exception e){throw new IllegalStateException(e);}finally{abort();}
        }
    }
    static Map<String,Object> run(Path runtime,Path rom)throws Exception{
        long began=System.nanoTime();System.err.println("Actual worker/native combination: "+rom.getFileName());
        var result=new LinkedHashMap<String,Object>();long pidA,pidB,parentA,parentB;int video=0,audio=0,state=0;Status host,guest;boolean freshInitialEqual;
        try(var a=new Parent(runtime,rom,true)){
            pidA=a.pid;parentA=a.process.pid();a.feed(1,1800);a.await(1800);byte[] snapshot=a.snapshot(1800);Status before=a.status(false);
            check(before.loads==0,"Host never restored before checkpoint");check(before.snapshots.equals(List.of(0L,1800L)),"Only frame0 and frame1800 snapshots");
            check(before.digests.keySet().equals(new TreeSet<>(List.of(300L,600L,900L,1200L,1500L,1800L))),"Full digest every300 frames");
            check(before.saves==7,"Actual full saves = initial plus six digests");
            check(NativeSnapshotState.decode(snapshot,NativeSnapshotProfile.ROMS.get(rom.getFileName().toString()),1800).internalFrame()==1832,"Snapshot envelope binds native1832 to logical1800");
            try(var b=new Parent(runtime,rom,false)){
                pidB=b.pid;parentB=b.process.pid();freshInitialEqual=a.initialHash.equals(b.initialHash);
                check(pidA!=pidB&&parentA!=parentB,"Two independent parent/owned child pairs");check(a.fps==NativeSnapshotProfile.FPS&&a.fps==b.fps,"Actual native timing retained");
                check(a.compatibility.equals(NativeSnapshotProfile.COMPATIBILITY_ID)&&a.compatibility.equals(b.compatibility),"Fixed actual compatibility");
                b.restore(snapshot,1800,1801,120);Status blocked=b.status(true);check(blocked.frame==1801&&blocked.acks.isEmpty(),"No premature ACK at old goal with backlog >6");
                a.feed(1801,120);a.await(1920);check(a.status(false).loads==0,"Host kept running while guest was held in catchup");
                b.resume();b.await(1920);Status caught=b.status(false);check(caught.acks.equals(List.of(1914L)),"ACK only at six-frame remaining backlog");
                a.feed(1921,480);b.feed(1921,480);a.await(2400);b.await(2400);host=a.status(false);guest=b.status(false);
                check(host.marks.size()==600&&guest.marks.size()==600,"Every post-snapshot frame observed");
                for(int n=0;n<600;n++){
                    Mark x=host.marks.get(n),y=guest.marks.get(n);check(x.frame==1801+n&&y.frame==x.frame,"Exact authoritative observation order");
                    if(x.width!=y.width||x.height!=y.height||x.aspect!=y.aspect||x.rotation!=y.rotation||!x.video.equals(y.video))video++;
                    if(x.samples!=y.samples||!x.audio.equals(y.audio))audio++;
                }
                for(long frame:List.of(2100L,2400L)){check(host.digests.containsKey(frame)&&guest.digests.containsKey(frame),"Both full state digests present");if(!host.digests.get(frame).equals(guest.digests.get(frame)))state++;}
                check(host.loads==0&&guest.loads==1,"Only guest performs one verified authoritative-frame restore");
                check(host.snapshots.equals(List.of(0L,1800L)),"No intermediate large snapshots during postload run");
                result.put("guest_ack_frame",caught.acks.getFirst());result.put("snapshot_sha256",CabinetSyncState.hash(snapshot));result.put("snapshot_bytes",snapshot.length);
                result.put("host_digest_frames",host.digests.keySet());result.put("guest_digest_frames",guest.digests.keySet());
            }
            check(ProcessHandle.of(pidB).map(p->!p.isAlive()).orElse(true),"Guest exact native child is gone");
            a.feed(2401,120);a.await(2520);check(a.status(false).loads==0,"Cancelling guest did not reset or stop Host");check(ProcessHandle.of(pidA).map(ProcessHandle::isAlive).orElse(false),"Host own native child remains alive");
        }
        check(ProcessHandle.of(pidA).map(p->!p.isAlive()).orElse(true),"Host exact native child exited after own close");
        result.put("ok",video==0&&audio==0&&state==0);result.put("game",rom.getFileName().toString());result.put("fresh_initial_equal",freshInitialEqual);
        result.put("real_worker_factory_to_native_core",true);result.put("host_never_restored",true);result.put("guest_full_hash_verified_after_restore",true);
        result.put("compared_frames",600);result.put("video_mismatches",video);result.put("pcm_mismatches",audio);result.put("full_state_digest_comparisons",2);result.put("state_mismatches",state);
        result.put("no_ack_while_backlogged",true);result.put("ack_max_local_backlog_frames",6);result.put("host_frames_during_blocked_guest",120);result.put("host_frames_after_guest_cancel",120);
        result.put("host_snapshot_frames",List.of(0,1800));result.put("owned_children_exited",true);result.put("native_pids",List.of(pidA,pidB));result.put("parent_pids",List.of(parentA,parentB));
        result.put("production_code_sources_checked_per_parent",13);result.put("elapsed_seconds",(System.nanoTime()-began)/1e9);return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length==4&&args[0].equals("child")){child(Path.of(args[1]),Path.of(args[2]),Boolean.parseBoolean(args[3]));return;}
        if(args.length!=3)throw new IllegalArgumentException("runtime kof97 mslug2");
        var games=List.of(run(Path.of(args[0]),Path.of(args[1])),run(Path.of(args[0]),Path.of(args[2])));boolean ok=games.stream().allMatch(g->Boolean.TRUE.equals(g.get("ok")));
        System.out.println(json(Map.of("ok",ok,"real_core",true,"real_cabinet_sync_worker",true,"games",games,"assertions",assertions,"profile",NativeSnapshotProfile.COMPATIBILITY_ID,"minecraft_started",false,"visible_window_opened",false,"state_normalization_or_ignored_bytes",false)));
        if(!ok)System.exit(2);
    }
}
