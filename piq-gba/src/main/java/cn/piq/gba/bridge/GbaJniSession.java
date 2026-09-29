// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.bridge;

import cn.piq.retro.libretro.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Local consent required by caller. Owner-only native calls; no forced native-thread termination. */
public final class GbaJniSession implements GbaSession {
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private static volatile GbaJniSession owned;
    private static volatile boolean shuttingDown;
    static {Runtime.getRuntime().addShutdownHook(new Thread(GbaJniSession::shutdown,"PIQ-GBA-JNI-final-save"));}
    private final ArrayBlockingQueue<Integer> inputs=new ArrayBlockingQueue<>(128);
    private final Object media=new Object();
    private final CountDownLatch finished=new CountDownLatch(1);
    private final Path rom,saves;
    private volatile boolean closed,ready;
    private volatile String failure;
    private volatile LibretroRuntime core;
    private Thread owner;
    private int offered,held;
    private int batterySize;
    private int[] latest;
    private final short[] audio=new short[32768];
    private int audioHead,audioSize;
    public static LibretroProfile profile(){return new LibretroProfile("mGBA","gba",false,List.of(1),false,
            Map.of("mgba_use_bios","OFF","mgba_skip_bios","ON"),Map.of("windows-x64",new LibretroProfile.Artifact(
            "/native-runtime/win-x64-v1/piq-gba/runtime/mgba_libretro.dll",GbaProcessSession.CORE_SHA)));}
    public GbaJniSession(Path rom,Path saves)throws IOException {
        this.rom=Objects.requireNonNull(rom).toAbsolutePath().normalize();this.saves=Objects.requireNonNull(saves).toAbsolutePath().normalize();
        String reason=LibretroRuntimes.jniUnavailableReason();if(!reason.isBlank())throw new IOException(reason);
        if(shuttingDown||!ACTIVE.compareAndSet(false,true))throw new IOException("GBA JNI 尚未安全退出");
        owned=this;
        try{owner=new Thread(this::run,"PIQ-GBA-JNI-owner");owner.setDaemon(true);if(shuttingDown)closed=true;owner.start();}
        catch(RuntimeException|Error failure){owned=null;ACTIVE.set(false);throw failure;}
    }
    public static boolean active(){return ACTIVE.get();}
    public static void shutdown(){shuttingDown=true;var session=owned;if(session!=null){session.close();if(!session.awaitClosed(5000))System.getLogger(GbaJniSession.class.getName()).log(System.Logger.Level.WARNING,"GBA JNI final save/close unconfirmed; last durable save retained, native thread was not killed");}}
    public boolean isReady(){return ready&&!closed;}
    public String error(){var c=core;var nativeError=c==null?"":c.diagnosticError();return failure!=null?failure:nativeError.isBlank()?null:nativeError;}
    public synchronized void offerInput(int mask){
        if((mask&~0xfff)!=0)throw new IllegalArgumentException("GBA input");
        if(closed||offered==mask)return;
        if(!inputs.offer(mask)){failure="GBA JNI 输入队列超限";close();return;}offered=mask;
    }
    public synchronized void clearInput(){inputs.clear();held=offered=0;}
    private synchronized int nextInput(){Integer next=inputs.poll();if(next!=null)held=next;return held;}
    public GbaProcessSession.Frame pollFrame(){synchronized(media){if(latest==null)return null;var pcm=new short[audioSize];for(int i=0;i<pcm.length;i++)pcm[i]=audio[(audioHead+i)%audio.length];var frame=new GbaProcessSession.Frame(latest,pcm);latest=null;audioHead=audioSize=0;return frame;}}
    public void close(){closed=true;ready=false;clearInput();synchronized(media){latest=null;audioHead=audioSize=0;}if(owner!=null)LockSupport.unpark(owner);}
    public boolean awaitClosed(long millis){if(millis<0||millis>6000)throw new IllegalArgumentException("wait bound");try{return finished.await(millis,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    private void run(){
        try {
            if(closed)return;
            if(!Files.isRegularFile(rom,LinkOption.NOFOLLOW_LINKS)||!rom.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gba")||Files.size(rom)>32L*1024*1024)throw new IOException("GBA ROM 路径或大小无效");
            byte[] bytes;try(var in=Files.newInputStream(rom,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(32*1024*1024+1);}
            if(bytes.length<192||bytes.length>32*1024*1024)throw new IOException("GBA ROM 大小无效");
            String sha=HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var store=new GbaSaveStore(saves.resolve("jni-trial-v1-mgba-e31759b"),sha);byte[] saved=store.load();batterySize=saved.length;
            if(closed)return;
            core=new LibretroJniRuntime(profile(),GbaJniSession.class);verify(core.load(bytes));
            if(!core.coreVersion().equals("0.11-219-e31759b"))throw new IOException("mGBA 核心版本不匹配");
            var initial=core.saveMemory();
            if(initial.rtc().length!=0)throw new IOException("GBA JNI 本期只接电池 RAM；该核心 RTC 内存格式待适配");
            if(saved.length>0){
                byte[] ram=initial.ram();
                if(!GbaProtocol.saveSize(ram.length)||saved.length>ram.length)throw new IOException("GBA 试验存档长度不匹配，原档保留");
                // mGBA detects actual EEPROM/SRAM size on execution. Prefix initialization is
                // allowed only in the fresh, not-yet-run autodetect buffer, like the old adapter.
                Arrays.fill(ram,(byte)0xff);System.arraycopy(saved,0,ram,0,saved.length);
                core.restoreSaveMemory(new LibretroSaveMemory(ram,new byte[0]));
            }
            var resampler=new PcmResampler();long due=System.nanoTime(),nextSave=due+TimeUnit.SECONDS.toNanos(5);ready=!closed;
            while(!closed){
                var output=core.run(List.of(new LibretroProcess.Controls(new int[]{nextInput()},0)),3);verify(output.info());
                byte[] rgba=output.rgba();if(rgba.length!=240*160*4)throw new IOException("GBA JNI 画面大小异常");
                var pixels=new int[240*160];for(int i=0;i<pixels.length;i++){int p=i*4;pixels[i]=0xff000000|(rgba[p]&255)|((rgba[p+1]&255)<<8)|((rgba[p+2]&255)<<16);}
                short[] pcm=resampler.convert(output.stereo(),output.stereo().length,(int)output.info().sampleRate());
                synchronized(media){if(!closed){latest=pixels;for(short sample:pcm){if(audioSize==audio.length){audioHead=(audioHead+2)%audio.length;audioSize-=2;}audio[(audioHead+audioSize++)%audio.length]=sample;}}}
                long now=System.nanoTime();if(now>=nextSave){save(store);nextSave=now+TimeUnit.SECONDS.toNanos(5);}
                due+=(long)(1e9/output.info().fps());if(due<now-TimeUnit.SECONDS.toNanos(1))due=now;
                if(due>now)LockSupport.parkNanos(due-now);
            }
            // Never replace a valid save after failed native execution.
            if(failure==null&&error()==null)save(store);
        }catch(Exception|LinkageError e){failure="GBA JNI："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        finally {
            ready=false;closed=true;clearInput();
            try{if(core!=null)core.close();owned=null;ACTIVE.set(false);finished.countDown();}
            catch(RuntimeException|LinkageError e){failure="GBA JNI 关闭未确认，请正常重启客户端";/* keep active slot */}
        }
    }
    private void save(GbaSaveStore store)throws IOException {
        var memory=core.saveMemory();if(memory.rtc().length!=0)throw new IOException("RTC format changed");
        byte[] ram=batteryBytes(memory.ram(),batterySize);store.save(ram);if(ram.length>0)batterySize=ram.length;
    }
    /** Keep a known EEPROM/SRAM length while mGBA still exposes its all-FF autodetect tail.
     * No non-FF byte is discarded: any actual extension is saved in full. */
    public static byte[] batteryBytes(byte[] ram,int previousSize) {
        if(ram.length==131072&&GbaProtocol.saveSize(previousSize)&&previousSize<ram.length){
            boolean untouched=true;for(int i=previousSize;i<ram.length;i++)if(ram[i]!=(byte)0xff){untouched=false;break;}
            if(untouched)return Arrays.copyOf(ram,previousSize);
        }
        return ram;
    }
    private static void verify(LibretroProcess.Info info)throws IOException{if(info.width()!=240||info.height()!=160||info.fps()<59||info.fps()>61||info.sampleRate()!=65536)throw new IOException("mGBA GBA 几何或时序不匹配");}
}
