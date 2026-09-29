package cn.piq.nativearcade.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Owns exactly one dedicated native child. A process boundary, not an OS sandbox. */
public final class NativeProcessSession implements AutoCloseable {
    public static final String HELPER_NAME="piq-native-helper-v4.jar";
    private static final String HELPER_SHA="51A1A6A0A326E5E855647A414DBB78894CA01E9D766627EF272CE59BC809A304";
    public record Frame(int width,int height,int[] abgr,float displayAspect,int rotation,short[] pcm48k){}
    record Input(int command,int p1,int p2,int p3,int p4,int port){
        Input release(int target,int keepMask){return command==BridgeProtocol.INPUT4?new Input(command,target==0?p1&keepMask:p1,target==1?p2&keepMask:p2,target==2?p3&keepMask:p3,target==3?p4&keepMask:p4,port):this;}
    }
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private static final Object LAUNCH_LOCK=new Object();
    private static boolean shuttingDown;
    private static Process ownedProcess;
    static{
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            Process exact;
            synchronized(LAUNCH_LOCK){shuttingDown=true;exact=ownedProcess;if(exact!=null)exact.destroyForcibly();}
            if(exact!=null)try{exact.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        },"PIQ native exact-child shutdown"));
    }
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<String> error=new AtomicReference<>();
    // Reserve hard + soft control slots for every port; ordinary input remains capped at 128.
    private final ArrayBlockingQueue<Input> inputs=new ArrayBlockingQueue<>(136);
    private final Object frameLock=new Object();
    private Frame latestFrame;
    private final short[] pendingPcm=new short[32768];
    private int pendingStart,pendingSize;
    private final StringBuilder diagnostic=new StringBuilder();
    private volatile Process process;
    private volatile long lastFrame=System.nanoTime();
    private volatile boolean ready;
    private volatile boolean terminated;
    private final Path runtime,rom;private final String driver;
    private int lastP1,lastP2,lastP3,lastP4;
    public NativeProcessSession(Path runtimeDir,Path romZip)throws IOException{
        this(runtimeDir,romZip,driverName(romZip));
    }
    public NativeProcessSession(Path runtimeDir,Path romZip,String driver)throws IOException{
        if(!System.getProperty("os.name","").startsWith("Windows")||!System.getProperty("os.arch","").equals("amd64"))
            throw new IOException("Native arcade preview requires Windows x64");
        if(driver==null||!driver.matches("[a-z0-9_]{1,32}"))throw new IOException("Invalid arcade driver name");
        this.runtime=runtimeDir.toAbsolutePath().normalize();this.rom=romZip.toAbsolutePath().normalize();this.driver=driver;
        if(!rom.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")||!Files.isRegularFile(rom,LinkOption.NOFOLLOW_LINKS)
            ||Files.size(rom)<22||Files.size(rom)>BridgeProtocol.MAX_ROM)throw new IOException("ROM ZIP must be a regular file, at most 64 MiB");
        for(String file:List.of("mame_libretro.dll","jna-5.14.0.jar",HELPER_NAME))
            if(!Files.isRegularFile(runtime.resolve(file),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing runtime file: "+file);
        if(!ACTIVE.compareAndSet(false,true))throw new IOException("A native arcade process is already active or stopping");
        try{Thread owner=new Thread(this::run,"PIQ native arcade process owner");owner.setDaemon(true);owner.start();}
        catch(Throwable ex){ACTIVE.set(false);throw ex;}
    }
    private static String driverName(Path rom)throws IOException{
        String n=rom.getFileName().toString();if(!n.toLowerCase(Locale.ROOT).endsWith(".zip"))throw new IOException("Only ZIP ROM sets are supported");
        return n.substring(0,n.length()-4);
    }
    public int maxPlayers(){return 4;}
    public void offerInput(int p1,int p2){offerInputs(p1,p2,0,0);}
    public synchronized void offerInputs(int p1,int p2,int p3,int p4){
        NativeInputPorts.checkMask(p1);NativeInputPorts.checkMask(p2);NativeInputPorts.checkMask(p3);NativeInputPorts.checkMask(p4);
        if(closed.get()||(p1==lastP1&&p2==lastP2&&p3==lastP3&&p4==lastP4))return;
        lastP1=p1;lastP2=p2;lastP3=p3;lastP4=p4;
        if(inputs.size()>=128||!inputs.offer(new Input(BridgeProtocol.INPUT4,p1,p2,p3,p4,-1)))fail("Input queue overflow; session stopped");
    }
    public void offerInput(int p1){offerInput(p1,0);}
    public synchronized void clearInput(){
        lastP1=lastP2=lastP3=lastP4=0;inputs.clear();if(!closed.get())inputs.offer(new Input(BridgeProtocol.CLEAR,0,0,0,0,-1));
    }
    public synchronized void releasePort(int port){
        releasePort(port,false);
    }
    public synchronized void releaseGameplayPortKeepingCoin(int port){
        releasePort(port,true);
    }
    private void releasePort(int port,boolean keepCoin){
        NativeInputPorts.checkPort(port);if(closed.get())return;
        int keep=keepCoin?4:0;
        switch(port){case 0->lastP1&=keep;case 1->lastP2&=keep;case 2->lastP3&=keep;case 3->lastP4&=keep;}
        if(!projectPending(inputs,port,keepCoin))fail("Controller release queue overflow; session stopped");
    }
    /** Real parent FIFO transform, exposed package-locally for inert queue/pipe tests. */
    static boolean projectPending(ArrayBlockingQueue<Input> inputs,int port,boolean keepCoin){
        NativeInputPorts.checkPort(port);int keep=keepCoin?4:0;
        List<Input> retained=new ArrayList<>();inputs.drainTo(retained);
        Input previous=null;
        for(Input old:retained){
            // A soft release MUST NOT erase an earlier hard release (old seat ownership).
            boolean redundant=old.port==port&&(old.command==BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN
                    ||!keepCoin&&old.command==BridgeProtocol.RELEASE_PORT);
            if(redundant)continue;
            Input projected=old.release(port,keep);
            if(projected.command==BridgeProtocol.INPUT4&&projected.equals(previous))continue;
            if(!inputs.offer(projected))throw new IllegalStateException("Reserved input queue capacity lost");
            previous=projected;
        }
        return inputs.offer(new Input(keepCoin?BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN:BridgeProtocol.RELEASE_PORT,0,0,0,0,port));
    }
    /** Nonblocking latest picture plus ALL queued audio since the previous poll, capped at 341 ms. */
    public Frame pollFrame(){synchronized(frameLock){
        if(latestFrame==null)return null;
        Frame f=latestFrame;latestFrame=null;
        short[] audio=new short[pendingSize];
        for(int i=0;i<pendingSize;i++)audio[i]=pendingPcm[(pendingStart+i)%pendingPcm.length];
        pendingStart=pendingSize=0;
        return new Frame(f.width,f.height,f.abgr,f.displayAspect,f.rotation,audio);
    }}
    public String error(){return error.get();}
    public boolean isReady(){return ready&&!closed.get();}
    public boolean isClosed(){return closed.get();}
    /** Instance-specific completion, unlike the shared ACTIVE admission lease. */
    public boolean isTerminated(){return terminated;}
    public String diagnostics(){synchronized(diagnostic){return diagnostic.toString();}}
    public static boolean hasLiveSession(){return ACTIVE.get();}
    // The optional synchronous bridge shares the same exact-child lease with the
    // legacy/media bridge. It does not load JNA into the Minecraft JVM.
    static void acquireStepSlot()throws IOException{
        synchronized(LAUNCH_LOCK){
            if(shuttingDown||!ACTIVE.compareAndSet(false,true))throw new IOException("A native arcade process is already active or stopping");
        }
    }
    static Process launchStepChild(ProcessBuilder builder)throws IOException{
        synchronized(LAUNCH_LOCK){
            if(shuttingDown||!ACTIVE.get()||ownedProcess!=null)throw new IOException("Native process lease expired");
            return ownedProcess=builder.start();
        }
    }
    static void releaseStepSlot(Process exact){
        synchronized(LAUNCH_LOCK){
            if(exact!=null&&exact.isAlive())throw new IllegalStateException("Cannot release a live native child");
            if(ownedProcess!=exact)throw new IllegalStateException("Native child ownership changed");
            ownedProcess=null;ACTIVE.set(false);
        }
    }
    private void fail(String reason){error.compareAndSet(null,reason);close();}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        inputs.clear();inputs.offer(new Input(BridgeProtocol.CLOSE,0,0,0,0,-1));
        synchronized(frameLock){latestFrame=null;pendingStart=pendingSize=0;}
        Process p=process;if(p!=null)p.destroyForcibly(); // Exact owned child, never process-name termination.
    }
    private void run(){
        Path owned=null;Process child=null;
        try{
            try{verify(runtime.resolve(HELPER_NAME),HELPER_SHA);}
            catch(IOException mismatch){throw new IOException("Matching v4 coin-safe helper required; install the versioned helper bundled with Native 0.1.1",mismatch);}
            verify(runtime.resolve("mame_libretro.dll"),BridgeProtocol.CORE_SHA);
            verify(runtime.resolve("jna-5.14.0.jar"),BridgeProtocol.JNA_SHA);
            if(closed.get())return;
            owned=Files.createTempDirectory("piq-native-owned-");
            Path staged=NativeRomStaging.stage(rom,driver,owned);
            String cp=runtime.resolve(HELPER_NAME)+File.pathSeparator+runtime.resolve("jna-5.14.0.jar");
            Path java=Path.of(System.getProperty("java.home"),"bin","java.exe");
            synchronized(LAUNCH_LOCK){
                if(shuttingDown||closed.get())return;
                child=new ProcessBuilder(java.toString(),"-Xmx256m","-Djna.nosys=true","-cp",cp,
                    "cn.piq.nativearcade.bridge.NativeCoreWorker",runtime.resolve("mame_libretro.dll").toString(),staged.toString())
                    .directory(owned.toFile()).start();
                process=child;ownedProcess=child;
            }
            lastFrame=System.nanoTime();
            if(closed.get())child.destroyForcibly();
            Process exact=child;
            daemon("PIQ native log drain",()->drain(exact.getErrorStream()));
            daemon("PIQ native input pipe",()->writeInputs(exact));
            daemon("PIQ native watchdog",()->watch(exact));
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(child.getInputStream(),65536))){
                if(in.readInt()!=BridgeProtocol.MAGIC||in.readInt()!=BridgeProtocol.VERSION)throw new IOException("Native helper protocol mismatch; install the matching v4 coin-safe helper runtime");
                while(!closed.get()){
                    if(in.readInt()!=BridgeProtocol.FRAME)throw new IOException("Unknown native record");
                    int w=in.readInt(),h=in.readInt();float aspect=in.readFloat();int rotation=in.readInt(),n=in.readInt();
                    BridgeProtocol.frameBounds(w,h,aspect,rotation,n);
                    int[] rgba=new int[w*h];for(int i=0;i<rgba.length;i++)rgba[i]=in.readInt();
                    short[] pcm=new short[n];for(int i=0;i<n;i++)pcm[i]=in.readShort();
                    lastFrame=System.nanoTime();ready=true;
                    synchronized(frameLock){
                        if(!closed.get()){
                            latestFrame=new Frame(w,h,rgba,aspect,rotation,new short[0]);
                            for(short sample:pcm){
                                if(pendingSize==pendingPcm.length){pendingStart=(pendingStart+1)%pendingPcm.length;pendingSize--;}
                                pendingPcm[(pendingStart+pendingSize)%pendingPcm.length]=sample;pendingSize++;
                            }
                        }
                    }
                }
            }
        }catch(Throwable ex){if(!closed.get()){System.getLogger(NativeProcessSession.class.getName()).log(System.Logger.Level.ERROR,"Native arcade process stopped",ex);fail("Native arcade: "+ex.getClass().getSimpleName()+": "+ex.getMessage());}}
        finally{
            closed.set(true);ready=false;
            if(child!=null){
                child.destroyForcibly();
                boolean interrupted=false;
                for(;;)try{child.waitFor();break;}catch(InterruptedException ex){interrupted=true;child.destroyForcibly();}
                if(interrupted)Thread.currentThread().interrupt();
            }
            if(owned!=null)cleanupOwned(owned);
            synchronized(LAUNCH_LOCK){if(ownedProcess==child)ownedProcess=null;}
            ACTIVE.set(false); // Released only after the exact native process has actually exited.
            terminated=true;
        }
    }
    private static void verify(Path path,String expected)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] buf=new byte[131072];for(int n;(n=in.read(buf))!=-1;)digest.update(buf,0,n);}
        if(!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected))throw new IOException("Runtime SHA-256 mismatch: "+path.getFileName());
    }
    private void writeInputs(Process child){
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))){
            while(child.isAlive()){Input next=inputs.poll(1,TimeUnit.SECONDS);if(next==null)continue;
                out.writeInt(next.command);if(next.command==BridgeProtocol.INPUT4){out.writeInt(next.p1);out.writeInt(next.p2);out.writeInt(next.p3);out.writeInt(next.p4);}
                else if(next.command==BridgeProtocol.RELEASE_PORT||next.command==BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN)out.writeInt(next.port);out.flush();
                if(next.command==BridgeProtocol.CLOSE)break;
            }
        }catch(Exception ex){if(!closed.get())fail("Native input pipe closed");}
    }
    private void watch(Process child){
        while(child.isAlive()&&!closed.get()){
            if(System.nanoTime()-lastFrame>TimeUnit.SECONDS.toNanos(15)){fail("Native core exceeded 15-second frame timeout");break;}
            try{Thread.sleep(100);}catch(InterruptedException ex){Thread.currentThread().interrupt();return;}
        }
    }
    private void drain(InputStream stream){
        try(InputStream in=stream){byte[] buf=new byte[2048];for(int n;(n=in.read(buf))!=-1;){
            synchronized(diagnostic){diagnostic.append(new String(buf,0,n,java.nio.charset.StandardCharsets.UTF_8));
                if(diagnostic.length()>16384)diagnostic.delete(0,diagnostic.length()-16384);}
        }}catch(IOException ignored){}
    }
    private static void daemon(String name,Runnable task){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
    private static void cleanupOwned(Path owned){
        // Only the fresh directory returned by createTempDirectory; no user-controlled delete target.
        try(var paths=Files.walk(owned)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())try{Files.deleteIfExists(p);}catch(IOException ignored){}}
        catch(IOException ignored){}
    }
}
