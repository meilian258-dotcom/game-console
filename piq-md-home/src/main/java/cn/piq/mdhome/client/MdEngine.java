// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.fcarcade.client.privateplay.*;
import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.mdhome.save.MdSaveCatalog;
import cn.piq.retro.api.RetroFrame;
import cn.piq.retro.libretro.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** One owner for native calls. Private constructors never publish; explicit public mode uses an authorized channel. */
public final class MdEngine implements PrivateEngine {
    private static final AtomicBoolean ACTIVE=new AtomicBoolean();
    private final Object controls=new Object(),media=new Object(),saveMonitor=new Object();
    private final MdPublicInputBuffer inputs;
    private final CompletableFuture<SaveResult> finished=new CompletableFuture<>();
    private final short[] sound=new short[32768];
    private final Thread owner;
    private final boolean saving,publicSession,resume;
    private final NetplayProcess.Persistence persistence;
    private final NetplaySaveState.Identity identity;
    private final Consumer<RetroFrame> mediaTap;
    private volatile boolean closing,paused,ready,initialized,publicSaving;
    private final AtomicBoolean resetRequested=new AtomicBoolean();
    private volatile String error,saveStatus;
    private volatile LibretroRuntime core;
    private int head,size;
    private long revision,publicFrame,lastCheckpoint=-1,commitDeadline;
    private CabinetFrame latest;
    private CompletableFuture<SaveResult> saveRequest;
    private CompletableFuture<Void> commit;
    public MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend){this(rom,root,backend,MdProfile.Core.GENESIS_PLUS_GX);}
    public static boolean active(){return ACTIVE.get();}
    public MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected){this(rom,root,backend,selected,true);}
    public MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected,boolean saving){
        this(rom,Objects.requireNonNull(root),backend,selected,saving,null,null,true,null);
    }
    /** Public player-hosted streaming only; not Netplay. Public identity currently pins GX/JNI. */
    public MdEngine(Path rom,LibretroRuntimes.Backend backend,NetplayProcess.Persistence persistence,
                    NetplaySaveState.Identity identity,boolean resume,Consumer<RetroFrame> mediaTap){
        this(rom,null,backend,MdProfile.Core.GENESIS_PLUS_GX,false,Objects.requireNonNull(persistence),
                Objects.requireNonNull(identity),resume,Objects.requireNonNull(mediaTap));
    }
    private MdEngine(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected,boolean saving,
                     NetplayProcess.Persistence persistence,NetplaySaveState.Identity identity,boolean resume,Consumer<RetroFrame> mediaTap){
        Objects.requireNonNull(rom);Objects.requireNonNull(backend);Objects.requireNonNull(selected);
        this.saving=saving;this.persistence=persistence;this.identity=identity;this.resume=resume;this.mediaTap=mediaTap;
        publicSession=persistence!=null;
        if(publicSession&&backend!=LibretroRuntimes.Backend.JNI_TRIAL){persistence.abort();throw new IllegalArgumentException("MD 公开串流当前仅支持 Genesis Plus GX / JNI");}
        inputs=new MdPublicInputBuffer(publicSession?2:1);
        saveStatus=publicSession?"等待服务器存档":saving?"私人本机存档":"不存档";
        if(!ACTIVE.compareAndSet(false,true)){if(publicSession)persistence.abort();throw new IllegalStateException("MD 上一局尚未安全退出");}
        try{owner=Thread.ofPlatform().daemon(true).name("PIQ-MD-owner").start(()->run(rom,root,backend,selected));}
        catch(RuntimeException|Error e){ACTIVE.set(false);if(publicSession)persistence.abort();throw e;}
    }
    public int maxPlayers(){return publicSession?2:1;}
    public boolean isReady(){return ready&&!closing;}
    public String error(){var c=core;String d=c==null?"":c.diagnosticError();return error!=null?error:d.isBlank()?null:d;}
    public String saveStatus(){return saveStatus;}
    public boolean canSave(){return publicSession&&publicSaving&&isReady();}
    public void offerInput(int p1,int p2){
        if((p1&~4095)!=0||(p2&~4095)!=0||(!publicSession&&p2!=0))throw new IllegalArgumentException("MD input capabilities");
        synchronized(controls){if(closing||paused)return;offerLocked(0,p1);if(publicSession)offerLocked(1,p2);}
    }
    /** Independent port updates cannot overwrite the other player's held or pending input. */
    public void offerPort(int port,int mask){
        if(port<0||port>=maxPlayers()||(mask&~4095)!=0)throw new IllegalArgumentException("MD input port/bits");
        synchronized(controls){if(!closing&&!paused)offerLocked(port,mask);}
    }
    private void offerLocked(int port,int mask){
        try{inputs.offer(port,mask);}catch(IllegalStateException overflow){error=overflow.getMessage();stopAndSave();}
    }
    public void clearInput(){synchronized(controls){inputs.clear();revision++;}}
    public void releasePort(int port){synchronized(controls){inputs.release(port);revision++;}}
    public void paused(boolean p){synchronized(controls){if(paused!=p){paused=p;clearInput();clearMedia();}}LockSupport.unpark(owner);}
    public CabinetFrame pollFrame(){
        synchronized(media){if(latest==null||closing||paused)return null;short[] pcm=new short[size];
            for(int i=0;i<size;i++)pcm[i]=sound[(head+i)%sound.length];
            var result=new CabinetFrame(latest.width(),latest.height(),latest.abgr(),latest.displayAspect(),0,pcm);
            latest=null;head=size=0;return result;}
    }
    private void clearMedia(){synchronized(media){latest=null;head=size=0;}}
    public CompletableFuture<SaveResult> stopAndSave(){
        boolean cancelStartup;
        synchronized(controls){closing=true;ready=false;cancelStartup=!initialized;clearInput();clearMedia();}
        // Cancel a pending startup load, but keep a running game's channel for its final durable ACK.
        if(publicSession&&cancelStartup)persistence.abort();
        if(owner!=null)LockSupport.unpark(owner);return finished;
    }
    public void close(){stopAndSave();}
    public boolean requestReset(){if(!isReady())return false;resetRequested.set(true);clearInput();LockSupport.unpark(owner);return true;}
    public CompletableFuture<SaveResult> requestSave(){
        synchronized(saveMonitor){
            if(!canSave())return CompletableFuture.completedFuture(new SaveResult(false,publicSession&&!publicSaving?"本局不存档":"MD 主持尚未就绪"));
            if(saveRequest!=null&&!saveRequest.isDone())return saveRequest;
            saveRequest=new CompletableFuture<>();saveStatus="正在保存到服务器…";LockSupport.unpark(owner);return saveRequest;
        }
    }
    private void run(Path rom,Path root,LibretroRuntimes.Backend backend,MdProfile.Core selected){
        PrivateSaveStore store=null;PrivateSaveStore.Key key=null;boolean closed=true;
        SaveResult result=new SaveResult(false,"MD 尚未开始，原存档未更改");
        try{
            if(closing)return;byte[] content=MdRom.read(rom);PrivateSaveStore.Snapshot saved=null;
            if(publicSession){
                if(!MdSaveCatalog.identity(PrivateSaveStore.sha256(content)).equals(identity))throw new IllegalArgumentException("MD 游戏与公开保存身份不一致");
                byte[] checkpoint=persistence.load(identity);publicSaving=persistence.enabled();
                if(publicSaving&&checkpoint!=null){
                    var parts=NetplaySaveState.decode(checkpoint,identity);
                    if(parts.rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");
                    publicFrame=parts.frame();
                    if(publicSaving&&resume)saved=new PrivateSaveStore.Snapshot(parts.state(),parts.ram());
                }
                saveStatus=publicSaving?"服务器存档已读取":"本局不存档";
            }else{
                key=new PrivateSaveStore.Key("md",MdProfile.saveNamespace(selected,backend),PrivateSaveStore.sha256(content));
                store=new PrivateSaveStore(root);saved=saving?store.load(key).orElse(null):null;
            }
            if(closing)return;core=LibretroRuntimes.create(MdProfile.profile(selected),MdProfile.class,backend);check(core.load(content),selected);
            // GX exposes full SRAM capacity only before the first run; retain bridge exact-size checks.
            if(selected==MdProfile.Core.GENESIS_PLUS_GX&&saved!=null)
                core.restoreSaveMemory(new LibretroSaveMemory(MdSaves.startupRam(saved.sram(),core.saveMemory()),new byte[0]));
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),0);
            if(saved!=null){
                if(core.saveMemory().rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");
                if(selected==MdProfile.Core.BLASTEM)core.restoreSaveMemory(new LibretroSaveMemory(saved.sram(),new byte[0]));
                core.restore(saved.state());
                if(!Arrays.equals(saved.sram(),core.saveMemory().ram()))throw new IllegalStateException("MD SRAM 恢复不一致");
            }
            synchronized(controls){if(closing)return;initialized=true;ready=true;}
            var audio=new MdAudio();long due=System.nanoTime(),saveDue=due+30_000_000_000L;
            while(!closing){
                if(resetRequested.getAndSet(false)){core.reset();clearInput();clearMedia();audio=new MdAudio();due=System.nanoTime();saveDue=due+30_000_000_000L;}
                // RESET does not reset the public checkpoint sequence.
                if(publicSession)handlePublicSave();
                if(paused){due=System.nanoTime();LockSupport.parkNanos(5_000_000);continue;}
                int[] masks;long rev;synchronized(controls){masks=inputs.next();rev=revision;}
                var out=core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(selected,masks[0]),MdProfile.input(selected,masks[1])},0)),3);
                check(out.info(),selected);if(publicSession)publicFrame=Math.incrementExact(publicFrame);
                publish(out,audio,rev);long now=System.nanoTime();
                if(now>=saveDue){if(publicSession&&publicSaving)requestSave();else if(!publicSession&&saving)savePrivate(store,key);saveDue=now+30_000_000_000L;}
                long frame=(long)(1e9/out.info().fps());due+=frame;if(due<now-4*frame)due=now;if(due>now)LockSupport.parkNanos(due-now);
            }
        }catch(Exception|LinkageError e){error="MD："+detail(e);}
        finally{
            closing=true;ready=false;clearInput();clearMedia();
            if(error()!=null)result=new SaveResult(false,error()+"；原保存保留");
            else if(initialized&&publicSession){
                if(!publicSaving)result=new SaveResult(false,"本局不存档；未读取或写入进度，旧档保留");
                else try{finishPublicSave();result=new SaveResult(true,"MD 进度已保存到服务器");}
                catch(Exception e){result=new SaveResult(false,"MD 最终保存未确认："+detail(e)+"；保留最近确认版本");}
            }else if(initialized&&!saving)result=new SaveResult(false,"本卡设置为不存档；未读取或写入进度，旧档保留");
            else if(initialized){try{savePrivate(store,key);result=new SaveResult(true,"MD 私人进度已保存到本机（当前后端独立档）");}
                catch(Exception e){result=new SaveResult(false,"MD 保存失败："+detail(e)+"；原件保留");}}
            try{if(core!=null)core.close();}
            catch(RuntimeException|LinkageError e){closed=false;error="MD 关闭未确认，请正常重启客户端";result=new SaveResult(result.saved(),result.message()+"；"+error);}
            if(publicSession)persistence.abort();
            saveStatus=result.message();synchronized(saveMonitor){if(saveRequest!=null&&!saveRequest.isDone())saveRequest.complete(result);}
            if(closed)ACTIVE.set(false);finished.complete(result);
        }
    }
    /** Owner thread only; persistence.save must promptly enqueue its bounded asynchronous transfer. */
    private void handlePublicSave()throws Exception{
        if(!publicSaving)return;
        CompletableFuture<SaveResult> completed=null;boolean requested;
        synchronized(saveMonitor){
            if(commit!=null){
                if(!commit.isDone()){if(System.nanoTime()>commitDeadline)throw new TimeoutException("MD 保存等待服务器确认超时");return;}
                commit.get();commit=null;saveStatus="已保存到服务器";completed=saveRequest;saveRequest=null;
            }
            requested=saveRequest!=null&&!saveRequest.isDone();
        }
        // Neither native serialization nor completion callbacks may hold the caller-facing monitor.
        if(completed!=null)completed.complete(new SaveResult(true,"已保存到服务器"));
        if(requested){commit=Objects.requireNonNull(persistence.save(publicCheckpoint()));commitDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);}
    }
    private byte[] publicCheckpoint(){
        var m=core.saveMemory();if(m.rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");
        // Logical checkpoint sequence is monotonic even while paused, after RESET, or immediately on close.
        if(publicFrame<=lastCheckpoint)publicFrame=Math.incrementExact(lastCheckpoint);lastCheckpoint=publicFrame;
        return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,publicFrame,core.serialize(),m.ram(),m.rtc()));
    }
    private void finishPublicSave()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        if(commit!=null){awaitCommit(commit,deadline,45);commit=null;}
        awaitCommit(Objects.requireNonNull(persistence.save(publicCheckpoint())),deadline,45);
        awaitCommit(Objects.requireNonNull(persistence.finish()),deadline,10);
    }
    private static void awaitCommit(CompletableFuture<Void> future,long deadline,int seconds)throws Exception{
        long remaining=Math.min(TimeUnit.SECONDS.toNanos(seconds),deadline-System.nanoTime());
        if(remaining<=0)throw new TimeoutException("MD 最终保存超时");future.get(remaining,TimeUnit.NANOSECONDS);
    }
    private void savePrivate(PrivateSaveStore store,PrivateSaveStore.Key key)throws Exception{
        var m=core.saveMemory();if(m.rtc().length!=0)throw new IllegalStateException("MD RTC 待适配");store.save(key,core.serialize(),m.ram());
    }
    private static String detail(Throwable e){
        while((e instanceof ExecutionException||e instanceof CompletionException)&&e.getCause()!=null)e=e.getCause();
        return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
    }
    static void check(LibretroProcess.Info i,MdProfile.Core selected){
        boolean rate=selected==MdProfile.Core.GENESIS_PLUS_GX?i.sampleRate()==44100:i.sampleRate()>=50000&&i.sampleRate()<=54000;
        if(i.width()<1||i.height()<1||i.width()>720||i.height()>576||!Double.isFinite(i.fps())||i.fps()<49||i.fps()>61||!rate
                ||!Float.isFinite(i.aspect())||i.aspect()<=0||i.aspect()>4)throw new IllegalStateException("MD AV 格式不匹配");
    }
    private void publish(LibretroProcess.Output out,MdAudio audio,long rev){
        var i=out.info();byte[] rgba=out.rgba();if(rgba.length!=i.width()*i.height()*4)throw new IllegalArgumentException("MD frame bounds");
        int[] pixels=new int[i.width()*i.height()];for(int p=0;p<pixels.length;p++){int n=p*4;pixels[p]=0xff000000|(rgba[n]&255)|((rgba[n+1]&255)<<8)|((rgba[n+2]&255)<<16);}
        short[] pcm=audio.convert(out.stereo(),(int)i.sampleRate());
        synchronized(controls){
            if(closing||paused||rev!=revision)return;
            synchronized(media){latest=new CabinetFrame(i.width(),i.height(),pixels,i.aspect(),0,new short[0]);for(short sample:pcm){if(size==sound.length){head=(head+2)%sound.length;size-=2;}sound[(head+size++)%sound.length]=sample;}}
        }
        // Tap must be non-blocking and bound to the exact public generation; local arrays remain independent.
        if(mediaTap!=null)mediaTap.accept(new RetroFrame(i.width(),i.height(),pixels.clone(),i.aspect(),0,pcm.clone()));
    }
}
