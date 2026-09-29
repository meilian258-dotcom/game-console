// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Exact owned child; no native code or JNA in the Minecraft JVM. Not an OS sandbox. */
public final class GbaProcessSession implements GbaSession {
    public static final String CORE_SHA="D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B";
    public static final String JNA_SHA="34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6";
    public record Frame(int[] abgr,short[] pcm48k) {}
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private static final Object LAUNCH_LOCK=new Object();
    private static Process ownedProcess;private static GbaProcessSession ownedSession;private static boolean shuttingDown;
    private static long shutdownStarted;
    private static final System.Logger LOG=System.getLogger(GbaProcessSession.class.getName());
    static {Runtime.getRuntime().addShutdownHook(new Thread(GbaProcessSession::shutdown,"PIQ GBA save and shutdown exact child"));}
    private final CountDownLatch finished=new CountDownLatch(1);
    private final AtomicBoolean closed=new AtomicBoolean();private volatile boolean ready;
    private volatile String error;private volatile Process process;private volatile long progress=System.nanoTime();
    private final Object lock=new Object();private final ArrayDeque<Integer> inputs=new ArrayDeque<>();private int held,lastOffered;
    private int[] latest;private final short[] audio=new short[32768];private int audioHead,audioSize;
    private final Path runtime,rom,saves;private final String helperSha;private Thread owner;
    @FunctionalInterface interface ChildLauncher { Process start(ProcessBuilder builder)throws IOException; }
    private final ChildLauncher launcher;
    public GbaProcessSession(Path runtime,Path rom,Path saves,String helperSha)throws IOException {
        this(runtime,rom,saves,helperSha,ProcessBuilder::start);
    }
    GbaProcessSession(Path runtime,Path rom,Path saves,String helperSha,ChildLauncher launcher)throws IOException {
        if(!System.getProperty("os.name","").startsWith("Windows")||!Set.of("amd64","x86_64").contains(System.getProperty("os.arch","")))throw new IOException("GBA prototype requires Windows x64");
        if(helperSha==null||!helperSha.matches("[A-F0-9]{64}"))throw new IOException("Helper identity");
        this.runtime=Objects.requireNonNull(runtime).toAbsolutePath().normalize();this.rom=Objects.requireNonNull(rom).toAbsolutePath().normalize();this.saves=Objects.requireNonNull(saves).toAbsolutePath().normalize();this.helperSha=helperSha;this.launcher=Objects.requireNonNull(launcher);
        synchronized(LAUNCH_LOCK){
            if(shuttingDown)throw new IOException("GBA is shutting down");
            if(!ACTIVE.compareAndSet(false,true))throw new IOException("A GBA process is already active or stopping");
            ownedSession=this;
        }
        try{owner=new Thread(this::run,"PIQ GBA owned process");owner.setDaemon(true);owner.start();}
        catch(Throwable t){synchronized(LAUNCH_LOCK){if(ownedSession==this){ownedSession=null;ACTIVE.set(false);}}finished.countDown();throw t;}
    }
    public boolean isReady(){return ready&&!closed.get();}public String error(){return error;}public static boolean active(){return ACTIVE.get();}
    public void offerInput(int mask){if((mask&~0xfff)!=0)throw new IllegalArgumentException("Input mask");synchronized(lock){if(closed.get()||mask==lastOffered)return;if(inputs.size()>=128){fail("GBA input queue overflow");return;}inputs.addLast(mask);lastOffered=mask;}}
    public void clearInput(){synchronized(lock){inputs.clear();held=lastOffered=0;}}
    public Frame pollFrame(){synchronized(lock){if(latest==null)return null;short[] pcm=new short[audioSize];for(int i=0;i<audioSize;i++)pcm[i]=audio[(audioHead+i)%audio.length];audioHead=audioSize=0;int[] p=latest;latest=null;return new Frame(p,pcm);}}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;ready=false;clearInput();synchronized(lock){latest=null;audioHead=audioSize=0;}if(owner!=null)LockSupport.unpark(owner);}
    /** Normal UI close stays asynchronous. Only application shutdown waits, with a hard deadline. */
    public static void shutdown(){
        GbaProcessSession session;long start;
        synchronized(LAUNCH_LOCK){if(!shuttingDown){shuttingDown=true;shutdownStarted=System.nanoTime();}start=shutdownStarted;session=ownedSession;}
        if(session==null)return;
        session.close();
        if(!session.awaitUntil(start+TimeUnit.SECONDS.toNanos(5))){
            LOG.log(System.Logger.Level.WARNING,"GBA shutdown save did not finish within 5 seconds; last durable save is preserved");
            Process exact;
            synchronized(LAUNCH_LOCK){exact=ownedSession==session?ownedProcess:null;}
            // Never hold the publication lock during process creation or an OS/process operation.
            if(exact!=null)exact.destroyForcibly();
            session.awaitUntil(start+TimeUnit.SECONDS.toNanos(6));
        }
    }
    private boolean awaitUntil(long deadline){
        try{return finished.await(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    public boolean awaitClosed(long timeoutMillis){
        if(timeoutMillis<0||timeoutMillis>6000)throw new IllegalArgumentException("Close wait bound");
        try{return finished.await(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    private void fail(String message){if(error==null)error=message;close();}
    private static void verify(Path file,String sha,int max)throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>max)throw new IOException("Runtime file bound");
        byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(max+1);}
        if(bytes.length>max||!hash(bytes).equals(sha))throw new IOException("Runtime hash mismatch: "+file.getFileName());
    }
    private static String hash(byte[] data)throws NoSuchAlgorithmException{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private void run(){Path temp=null;Thread watchdog=null;Process child=null;
        try{
            if(closed.get())return;
            verify(runtime.resolve("mgba_libretro.dll"),CORE_SHA,8*1024*1024);verify(runtime.resolve("jna-5.14.0.jar"),JNA_SHA,4*1024*1024);verify(runtime.resolve("piq-gba-helper.jar"),helperSha,1024*1024);
            if(!Files.isRegularFile(rom,LinkOption.NOFOLLOW_LINKS)||!rom.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gba")||Files.size(rom)<192||Files.size(rom)>32*1024*1024)throw new IOException("GBA ROM must be a regular .gba file up to 32 MiB");
            byte[] bytes;try(var stream=Files.newInputStream(rom,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(32*1024*1024+1);}if(bytes.length<192||bytes.length>32*1024*1024)throw new IOException("ROM changed");
            GbaSaveStore store=new GbaSaveStore(saves.resolve("mgba-e31759b"),hash(bytes));byte[] initial=store.load();
            temp=Files.createTempDirectory("piq-gba-session-");Path staged=temp.resolve("game.gba");Files.write(staged,bytes,StandardOpenOption.CREATE_NEW);
            // Snapshot all approved runtime bytes into our ASCII temporary directory before loading.
            for(String name:List.of("mgba_libretro.dll","jna-5.14.0.jar","piq-gba-helper.jar"))Files.copy(runtime.resolve(name),temp.resolve(name));
            verify(temp.resolve("mgba_libretro.dll"),CORE_SHA,8*1024*1024);verify(temp.resolve("jna-5.14.0.jar"),JNA_SHA,4*1024*1024);verify(temp.resolve("piq-gba-helper.jar"),helperSha,1024*1024);
            if(closed.get())return;
            String cp=temp.resolve("piq-gba-helper.jar")+File.pathSeparator+temp.resolve("jna-5.14.0.jar");
            ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms32m","-Xmx256m","-cp",cp,"cn.piq.gba.bridge.GbaWorker",temp.resolve("mgba_libretro.dll").toString(),staged.toString(),temp.toString());
            builder.directory(temp.toFile());builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            synchronized(LAUNCH_LOCK){if(shuttingDown||closed.get())return;}
            child=launcher.start(builder);process=child;
            boolean cancelled;
            synchronized(LAUNCH_LOCK){ownedProcess=child;cancelled=shuttingDown||closed.get();}
            // Close may observe a published session whose ProcessBuilder.start has not returned.
            // Its eventual child still belongs only to this session and must never be initialized.
            if(cancelled){child.destroyForcibly();return;}
            progress=System.nanoTime();Process exact=child;
            watchdog=new Thread(()->{try{while(exact.isAlive()){if(System.nanoTime()-progress>TimeUnit.SECONDS.toNanos(10)){error="GBA helper timed out";closed.set(true);exact.destroyForcibly();return;}Thread.sleep(100);}}catch(InterruptedException ignored){Thread.currentThread().interrupt();}},"PIQ GBA exact-child watchdog");watchdog.setDaemon(true);watchdog.start();
            try(var out=new DataOutputStream(new BufferedOutputStream(child.getOutputStream()));var in=new DataInputStream(new BufferedInputStream(child.getInputStream(),65536))){
                out.writeInt(initial.length);out.write(initial);out.flush();
                if(in.readInt()!=GbaProtocol.MAGIC||in.readInt()!=GbaProtocol.VERSION)throw new IOException("GBA private protocol mismatch");
                double fps=in.readDouble();if(!Double.isFinite(fps)||fps<50||fps>65)throw new IOException("GBA timing bound");
                ready=true;progress=System.nanoTime();long due=progress;int frames=0;
                while(!closed.get()){
                    int mask;synchronized(lock){if(!inputs.isEmpty())held=inputs.removeFirst();mask=held;}
                    out.writeInt(GbaProtocol.STEP);out.writeInt(mask);out.flush();
                    if(in.readInt()!=GbaProtocol.STEP||in.readInt()!=240||in.readInt()!=160)throw new IOException("GBA frame geometry");
                    int count=in.readInt();if(count<0||count>GbaProtocol.MAX_PCM||(count&1)!=0)throw new IOException("GBA PCM bound");
                    int[] pixels=new int[240*160];for(int i=0;i<pixels.length;i++)pixels[i]=in.readInt();short[] pcm=new short[count];for(int i=0;i<count;i++)pcm[i]=in.readShort();
                    synchronized(lock){if(!closed.get()){latest=pixels;for(short sample:pcm){if(audioSize==audio.length){audioHead=(audioHead+2)%audio.length;audioSize-=2;}audio[(audioHead+audioSize++)%audio.length]=sample;}}}
                    progress=System.nanoTime();if(++frames%300==0)save(in,out,store);
                    due+=(long)(1_000_000_000.0/fps);long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);else if(wait<-TimeUnit.SECONDS.toNanos(1))due=System.nanoTime();
                }
                // Normal UI exit flushes SRAM on the owner; never block the render thread.
                save(in,out,store);out.writeInt(GbaProtocol.CLOSE);out.flush();
            }
        }catch(Throwable t){LOG.log(System.Logger.Level.ERROR,"GBA process stopped",t);if(error==null)error="GBA: "+t.getClass().getSimpleName()+": "+t.getMessage();}
        finally{
            ready=false;closed.set(true);
            if(child!=null){try{if(!child.waitFor(2,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(2,TimeUnit.SECONDS);}}catch(InterruptedException e){child.destroyForcibly();Thread.currentThread().interrupt();}}
            if(watchdog!=null)watchdog.interrupt();
            if(child!=null&&child.isAlive()){
                // A slow OS termination must not release ACTIVE or delete a live child's runtime.
                Process exact=child;Path cleanup=temp;
                Thread.ofPlatform().daemon(true).name("PIQ GBA exact-child reaper").start(()->{
                    while(exact.isAlive()){exact.destroyForcibly();try{exact.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* Keep this exact child owned. */}}
                    complete(cleanup,exact);
                });
            }else complete(temp,child);
        }
    }
    private void complete(Path temp,Process child){
        if(temp!=null)try(var paths=Files.walk(temp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}catch(IOException ignored){}
        synchronized(lock){inputs.clear();latest=null;audioHead=audioSize=0;}
        process=null;
        synchronized(LAUNCH_LOCK){if(ownedProcess==child)ownedProcess=null;if(ownedSession==this){ownedSession=null;ACTIVE.set(false);}}
        if(error!=null)LOG.log(System.Logger.Level.WARNING,"GBA session closed with an error; final save may be incomplete: "+error);
        finished.countDown();
    }
    private void save(DataInputStream in,DataOutputStream out,GbaSaveStore store)throws IOException {out.writeInt(GbaProtocol.SAVE);out.flush();if(in.readInt()!=GbaProtocol.SAVE)throw new IOException("Save response");int n=in.readInt();if(n!=0&&!GbaProtocol.saveSize(n))throw new IOException("Save bound");byte[] save=in.readNBytes(n);if(save.length!=n)throw new EOFException();store.save(save);progress=System.nanoTime();}
}
