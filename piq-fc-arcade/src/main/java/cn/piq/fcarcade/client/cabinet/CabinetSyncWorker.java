package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.retro.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** One worker owns every core call, including factory/state/close. No Minecraft or network calls. */
public final class CabinetSyncWorker implements RetroEmulator {
    @FunctionalInterface public interface Factory{Opened open() throws Exception;default void requestClose(){}}
    public record Opened(CabinetSyncCore core,String romHash){}
    public record Event(int kind,long frame,String hash,String compatibility,int fpsMilli,UUID token,byte[] state){
        public static final int HELLO=0,DIGEST=1,SNAPSHOT=2,RESTORED=3,RESYNC=4;
    }
    private record Restore(UUID token,long frame,long goal,byte[] state,String hash,long revision){}
    private final Factory factory;private final boolean host;
    private final ArrayBlockingQueue<CabinetSyncTimeline.Step> inputs=new ArrayBlockingQueue<>(7200);
    private final ArrayBlockingQueue<Event> events=new ArrayBlockingQueue<>(12);
    private final AtomicReference<RetroFrame> picture=new AtomicReference<>();
    // 300 ms of stereo PCM. Droppable video never owns the pending audio queue.
    private final short[] audio=new short[28800];private int audioRead,audioCount;
    private final AtomicReference<Event> checkpoint=new AtomicReference<>();
    private final AtomicReference<Restore> restore=new AtomicReference<>();
    private final Thread thread;
    private volatile boolean closed,ready,waiting;
    private volatile String error;
    private volatile CabinetSyncCore activeCore;
    private volatile int ports;
    private int snapshotInterval=300;
    private long frame,expectedInput;private UUID restoreToken;private long restoreGoal;
    private final AtomicLong revision=new AtomicLong();
    public CabinetSyncWorker(Factory factory,boolean host){
        this.factory=Objects.requireNonNull(factory);this.host=host;waiting=!host;
        thread=new Thread(this::run,"PIQ-Cabinet-Sync");thread.setDaemon(true);thread.start();
    }
    public Event pollEvent(){if(closed)return null;var event=events.poll();return event==null?checkpoint.getAndSet(null):event;}
    public boolean frames(List<CabinetSyncTimeline.Step> batch){
        if(closed||batch==null||batch.size()>CabinetSyncTimeline.MAX_BATCH)return false;
        synchronized(inputs){
            if(inputs.remainingCapacity()<batch.size())return false;
            long previous=expectedInput;
            for(var s:batch){if(s.frame()!=previous+1)return false;previous=s.frame();}
            expectedInput=previous;inputs.addAll(batch);return true;
        }
    }
    /** A server-issued restore only replaces this guest's worker state, never the host. */
    public void beginRestore(long start){
        if(host||closed||start<0)throw new IllegalStateException("Host cannot be restored by a guest transaction");
        synchronized(inputs){waiting=true;revision.incrementAndGet();inputs.clear();expectedInput=start;restore.set(null);picture.set(null);clearAudio();events.clear();checkpoint.set(null);restoreToken=null;}
    }
    public void restore(UUID token,long start,long goal,byte[] state,String hash){
        if(host||closed||token==null||start<0||goal<start||state==null||state.length<1||state.length>CabinetSyncCore.MAX_STATE_BYTES)throw new IllegalArgumentException("Invalid restore");
        if(!CabinetSyncState.validHash(hash))throw new IllegalArgumentException("State hash");
        synchronized(inputs){if(!waiting)throw new IllegalStateException("Restore must begin before state delivery");restore.set(new Restore(token,start,goal,state,hash,revision.get()));}LockSupport.unpark(thread);
    }
    public void requestResync(){if(!closed)events.offer(new Event(Event.RESYNC,frame,"","",0,null,null));}
    private byte[] state(CabinetSyncCore core)throws Exception{
        byte[] value=core.saveState();if(value==null||value.length<1||value.length>CabinetSyncCore.MAX_STATE_BYTES)throw new IllegalStateException("Core snapshot outside bounded limit");return value;
    }
    private void checkpoint(CabinetSyncCore core,long before)throws Exception{
        byte[] value=state(core);String hash=CabinetSyncState.hash(value);
        synchronized(inputs){if(closed||waiting||before!=revision.get())return;
            if(!events.offer(new Event(Event.DIGEST,frame,hash,"",0,null,null)))throw new IllegalStateException("Sync event queue full");
            if(host&&frame%snapshotInterval==0)checkpoint.set(new Event(Event.SNAPSHOT,frame,hash,"",0,null,value));}
    }
    private void clearAudio(){audioRead=audioCount=0;}
    private void appendAudio(short[] samples){
        int start=Math.max(0,samples.length-audio.length),n=samples.length-start;
        int discard=Math.max(0,audioCount+n-audio.length);audioRead=(audioRead+discard)%audio.length;audioCount-=discard;
        for(int i=start;i<samples.length;i++)audio[(audioRead+audioCount++)%audio.length]=samples[i];
    }
    private void run(){
        CabinetSyncCore core=null;
        try{
            Opened opened=Objects.requireNonNull(factory.open());core=Objects.requireNonNull(opened.core());
            activeCore=core;
            if(closed)return;
            ports=core.maxPlayers();double fps=core.targetFps();String compatibility=core.compatibilityId();
            snapshotInterval=core.snapshotIntervalFrames();
            if(ports<1||ports>4||!Double.isFinite(fps)||fps<40||fps>80||compatibility==null||compatibility.isBlank()||compatibility.length()>256
                    ||compatibility.chars().anyMatch(Character::isISOControl)||!CabinetSyncState.validHash(opened.romHash())
                    ||snapshotInterval<300||snapshotInterval>1800||snapshotInterval%300!=0)throw new IllegalStateException("Invalid sync core identity or snapshot interval");
            byte[] initial=state(core);String initialHash=CabinetSyncState.hash(initial);int rate=(int)Math.round(fps*1000);
            events.add(new Event(Event.HELLO,0,opened.romHash(),compatibility,rate,null,initialHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            if(host)checkpoint.set(new Event(Event.SNAPSHOT,0,initialHash,"",0,null,initial));ready=true;
            long next=System.nanoTime();
            while(!closed){
                Restore command; synchronized(inputs){command=restore.getAndSet(null);}
                if(command!=null){
                    if(!CabinetSyncState.hash(command.state).equals(command.hash))throw new IllegalStateException("Snapshot hash mismatch");
                    core.loadState(command.state,command.frame);if(!CabinetSyncState.hash(state(core)).equals(command.hash))throw new IllegalStateException("Core state restoration is not exact");
                    synchronized(inputs){if(command.revision==revision.get()&&!closed){frame=command.frame;restoreToken=command.token;restoreGoal=command.goal;waiting=false;clearAudio();next=System.nanoTime();}}
                }
                CabinetSyncTimeline.Step step=null;boolean catchup=false;long before=0;
                synchronized(inputs){
                    if(!closed&&!waiting){
                        if(restoreToken!=null&&frame>=restoreGoal&&inputs.size()<=6){events.add(new Event(Event.RESTORED,frame,"","",0,restoreToken,null));restoreToken=null;}
                        catchup=inputs.size()>6||restoreToken!=null;
                        if(catchup||System.nanoTime()>=next){before=revision.get();step=inputs.poll();if(step!=null&&step.frame()!=frame+1)throw new IllegalStateException("Authoritative frame gap");}
                    }
                }
                if(step==null){LockSupport.parkNanos(1_000_000);continue;}
                var value=core.runFrame(step.p1(),step.p2(),step.p3(),step.p4());frame=step.frame();
                if(value==null)throw new IllegalStateException("Core did not produce one exact frame");
                synchronized(inputs){
                    if(closed||waiting||before!=revision.get())continue;
                    if(catchup)clearAudio();else appendAudio(value.pcm48k());
                    picture.set(new RetroFrame(value.width(),value.height(),value.abgr(),value.displayAspect(),value.rotation(),new short[0]));
                }
                if(frame%300==0)checkpoint(core,before);
                next=catchup?System.nanoTime():Math.max(next+(long)(1e9/fps),System.nanoTime()-50_000_000);
            }
        }catch(Exception|LinkageError failure){if(!closed){System.getLogger(CabinetSyncWorker.class.getName()).log(System.Logger.Level.ERROR,"Cabinet sync worker stopped",failure);error=failure.toString();}}
        finally{ready=false;activeCore=null;if(core!=null)try{core.close();}catch(RuntimeException|LinkageError ignored){}synchronized(inputs){inputs.clear();events.clear();restore.set(null);picture.set(null);checkpoint.set(null);clearAudio();}}
    }
    @Override public boolean isReady(){return ready&&!closed&&error==null;}
    @Override public String error(){return error;}
    @Override public int maxPlayers(){return ports;}
    @Override public void offerInput(int p1,int p2){throw new UnsupportedOperationException("Use server-authoritative frames");}
    @Override public void offerInputs(int p1,int p2,int p3,int p4){throw new UnsupportedOperationException("Use server-authoritative frames");}
    @Override public void releasePort(int port){if(port<0||port>3)throw new IllegalArgumentException("Port");/* Server's next frame owns release. */}
    @Override public void clearInput(){/* Never mutate deterministic core input outside a server frame. */}
    @Override public RetroFrame pollFrame(){synchronized(inputs){var latest=closed?null:picture.getAndSet(null);if(latest==null)return null;short[] pcm=new short[audioCount];for(int i=0;i<pcm.length;i++)pcm[i]=audio[(audioRead+i)%audio.length];clearAudio();return new RetroFrame(latest.width(),latest.height(),latest.abgr(),latest.displayAspect(),latest.rotation(),pcm);}}
    @Override public void close(){
        synchronized(inputs){if(closed)return;closed=true;revision.incrementAndGet();events.clear();inputs.clear();restore.set(null);picture.set(null);checkpoint.set(null);clearAudio();}
        try{factory.requestClose();}catch(RuntimeException|LinkageError ignored){}
        var core=activeCore;if(core!=null)try{core.requestClose();}catch(RuntimeException|LinkageError ignored){}
        LockSupport.unpark(thread);
    }
}
