// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.fcarcade.client.privateplay.*;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.retro.libretro.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Owns every core/save call on one worker. Only private local presentation; never publishes data. */
public final class MdEngine implements PrivateEngine {
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private final Object controls=new Object(),media=new Object();
    private final ArrayDeque<Integer> inputs=new ArrayDeque<>();
    private final CompletableFuture<SaveResult> finished=new CompletableFuture<>();
    private final short[] sound=new short[32768];
    private final Thread owner;
    private volatile boolean closing,paused,ready;
    private volatile String error;
    private volatile LibretroRuntime core;
    private int offered,held,head,size;private boolean neutral=true;private long revision;
    private CabinetFrame latest;
    public MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend){
        this(rom,root,backend,MdProfile.Core.GENESIS_PLUS_GX);
    }
    public static boolean active(){return ACTIVE.get();}
    public MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected){
        Objects.requireNonNull(rom);Objects.requireNonNull(root);Objects.requireNonNull(backend);
        Objects.requireNonNull(selected);
        if(!ACTIVE.compareAndSet(false,true))throw new IllegalStateException("MD 上一局尚未安全退出");
        try{owner=Thread.ofPlatform().daemon(true).name("PIQ-MD-owner").start(()->run(rom,root,backend,selected));}catch(RuntimeException|Error e){ACTIVE.set(false);throw e;}
    }
    public int maxPlayers(){return 1;}
    public boolean isReady(){return ready&&!closing;}
    public String error(){var c=core;String d=c==null?"":c.diagnosticError();return error!=null?error:d.isBlank()?null:d;}
    public void offerInput(int p1,int p2){
        if((p1&~4095)!=0||p2!=0)throw new IllegalArgumentException("MD private P1 only");
        synchronized(controls){if(closing||paused)return;if(neutral){if(p1==0)neutral=false;return;}if(p1==offered)return;
            if(inputs.size()>=128){error="MD 输入队列超限";stopAndSave();return;}inputs.add(p1);offered=p1;}
    }
    public void clearInput(){synchronized(controls){inputs.clear();offered=held=0;neutral=true;revision++;}}
    public void releasePort(int port){if(port!=0)throw new IllegalArgumentException("MD P1 only");clearInput();}
    public void paused(boolean p){synchronized(controls){if(paused!=p){paused=p;clearInput();clearMedia();}}LockSupport.unpark(owner);}
    public CabinetFrame pollFrame(){synchronized(media){if(latest==null||closing||paused)return null;short[] pcm=new short[size];for(int i=0;i<size;i++)pcm[i]=sound[(head+i)%sound.length];var result=new CabinetFrame(latest.width(),latest.height(),latest.abgr(),latest.displayAspect(),0,pcm);latest=null;head=size=0;return result;}}
    private void clearMedia(){synchronized(media){latest=null;head=size=0;}}
    public CompletableFuture<SaveResult> stopAndSave(){closing=true;ready=false;clearInput();clearMedia();if(owner!=null)LockSupport.unpark(owner);return finished;}
    public void close(){stopAndSave();}
    private void run(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected){
        PrivateSaveStore store=null;PrivateSaveStore.Key key=null;boolean initialized=false,closed=true;
        SaveResult result=new SaveResult(false,"MD 尚未开始，原存档未更改");
        try{
            if(closing)return;byte[] content=MdRom.read(rom);
            key=new PrivateSaveStore.Key("md",MdProfile.saveNamespace(selected,backend),PrivateSaveStore.sha256(content));
            store=new PrivateSaveStore(root);var saved=store.load(key);if(closing)return;
            core=LibretroRuntimes.create(MdProfile.profile(selected),MdProfile.class,backend);check(core.load(content),selected);
            // GX exposes full SRAM capacity only before the first retro_run; later lengths are trimmed.
            // Restore the validated battery first, never loosen the shared bridge's exact-size checks.
            if(selected==MdProfile.Core.GENESIS_PLUS_GX&&saved.isPresent())
                core.restoreSaveMemory(new LibretroSaveMemory(MdSaves.startupRam(saved.get().sram(),core.saveMemory()),new byte[0]));
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),0);
            if(saved.isPresent()){
                var memory=core.saveMemory();if(memory.rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");
                if(selected==MdProfile.Core.BLASTEM)core.restoreSaveMemory(new LibretroSaveMemory(saved.get().sram(),new byte[0]));
                core.restore(saved.get().state());
                if(!Arrays.equals(saved.get().sram(),core.saveMemory().ram()))throw new IllegalStateException("MD SRAM 恢复不一致");
            }
            initialized=true;ready=!closing;var audio=new MdAudio();long due=System.nanoTime(),saveDue=due+30_000_000_000L;
            while(!closing){
                if(paused){due=System.nanoTime();LockSupport.parkNanos(5_000_000);continue;}
                int mask;long rev;synchronized(controls){if(!inputs.isEmpty())held=inputs.removeFirst();mask=held;rev=revision;}
                var out=core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(selected,mask),0},0)),3);check(out.info(),selected);
                publish(out,audio,rev);long now=System.nanoTime();
                if(now>=saveDue){save(store,key);saveDue=now+30_000_000_000L;}
                long frame=(long)(1e9/out.info().fps());due+=frame;if(due<now-4*frame)due=now;if(due>now)LockSupport.parkNanos(due-now);
            }
        }catch(Exception|LinkageError e){error="MD："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
        finally{
            closing=true;ready=false;clearInput();clearMedia();
            if(error()!=null)result=new SaveResult(false,error()+"；原保存保留");
            else if(initialized){try{save(store,key);result=new SaveResult(true,"MD 私人进度已保存到本机（当前后端独立档）");}catch(Exception e){result=new SaveResult(false,"MD 保存失败："+e.getMessage()+"；原件保留");}}
            try{if(core!=null)core.close();}catch(RuntimeException|LinkageError e){closed=false;error="MD 关闭未确认，请正常重启客户端";result=new SaveResult(result.saved(),result.message()+"；"+error);}
            if(closed)ACTIVE.set(false);finished.complete(result);
        }
    }
    private void save(PrivateSaveStore store,PrivateSaveStore.Key key)throws Exception{var m=core.saveMemory();if(m.rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");store.save(key,core.serialize(),m.ram());}
    static void check(LibretroProcess.Info i,MdProfile.Core selected){
        boolean rate=selected==MdProfile.Core.GENESIS_PLUS_GX?i.sampleRate()==44100:i.sampleRate()>=50000&&i.sampleRate()<=54000;
        if(i.width()<1||i.height()<1||i.width()>720||i.height()>576||!Double.isFinite(i.fps())||i.fps()<49||i.fps()>61||!rate
                ||!Float.isFinite(i.aspect())||i.aspect()<=0||i.aspect()>4)throw new IllegalStateException("MD AV 格式不匹配");
    }
    private void publish(LibretroProcess.Output out,MdAudio audio,long rev){
        var i=out.info();byte[] rgba=out.rgba();if(rgba.length!=i.width()*i.height()*4)throw new IllegalArgumentException("MD frame bounds");
        int[] pixels=new int[i.width()*i.height()];for(int p=0;p<pixels.length;p++){int n=p*4;pixels[p]=0xff000000|(rgba[n]&255)|((rgba[n+1]&255)<<8)|((rgba[n+2]&255)<<16);}
        short[] pcm=audio.convert(out.stereo(),(int)i.sampleRate());
        synchronized(controls){if(closing||paused||rev!=revision)return;synchronized(media){latest=new CabinetFrame(i.width(),i.height(),pixels,i.aspect(),0,new short[0]);for(short sample:pcm){if(size==sound.length){head=(head+2)%sound.length;size-=2;}sound[(head+size++)%sound.length]=sample;}}}
    }
}
