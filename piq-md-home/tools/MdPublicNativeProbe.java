// Original ROM + real pinned GX/JNI. Fake transport tests durability order, not Minecraft networking.
import cn.piq.mdhome.client.*;
import cn.piq.mdhome.save.MdSaveCatalog;
import cn.piq.retro.libretro.*;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.retro.storage.RuntimeWorkspace;
import cn.piq.retro.api.RetroFrame;
import cn.piq.fcarcade.netplay.*;
import cn.piq.fcarcade.client.privateplay.PrivateEngine;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class MdPublicNativeProbe {
    static int checks,slots;static Path rom;static NetplaySaveState.Identity identity;
    static final LibretroRuntimes.Backend JNI=LibretroRuntimes.Backend.JNI_TRIAL;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);System.out.println("OK "+why);}
    static void waitFor(BooleanSupplier ready,String why)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);check(ready.getAsBoolean(),why);}
    static void ready(MdEngine e)throws Exception{waitFor(()->e.isReady()||e.error()!=null,"startup resolves");check(e.isReady(),"engine ready: "+e.error());}
    static void free(){check(!MdEngine.active()&&NativeLibretroBridge.availableSlots()==slots,"owner and JNI slot released");}
    static final class Disk {volatile byte[] bytes;Disk(byte[] b){bytes=b;}}
    static final class Pending {final byte[] bytes;final CompletableFuture<Void> ack=new CompletableFuture<>();Pending(byte[] b){bytes=b.clone();}}
    static final class Channel implements NetplayProcess.Persistence {
        final Disk disk;final boolean enabled;final AtomicInteger loads=new AtomicInteger(),finishes=new AtomicInteger(),aborts=new AtomicInteger();
        final BlockingQueue<Pending> pending=new LinkedBlockingQueue<>();final Set<String> threads=ConcurrentHashMap.newKeySet();
        volatile boolean automatic=true,gateFinish,gateLoad;final CompletableFuture<Void> loadGate=new CompletableFuture<>(),finishAck=new CompletableFuture<>();
        Channel(Disk d,boolean enabled){disk=d;this.enabled=enabled;}
        public byte[] load(NetplaySaveState.Identity expected)throws Exception{threads.add(Thread.currentThread().getName());loads.incrementAndGet();if(gateLoad)loadGate.get(20,TimeUnit.SECONDS);if(!expected.equals(identity))throw new IllegalArgumentException("identity");return enabled&&disk.bytes!=null?disk.bytes.clone():null;}
        public boolean enabled(){return enabled;}
        public CompletableFuture<Void> save(byte[] bytes){threads.add(Thread.currentThread().getName());Pending p=new Pending(bytes);pending.add(p);if(automatic)ack(p);return p.ack;}
        void ack(Pending p){disk.bytes=p.bytes.clone();p.ack.complete(null);}
        public CompletableFuture<Void> finish(){threads.add(Thread.currentThread().getName());finishes.incrementAndGet();return gateFinish?finishAck:CompletableFuture.completedFuture(null);}
        public void abort(){aborts.incrementAndGet();loadGate.completeExceptionally(new IllegalStateException("cancelled"));}
        Pending next()throws Exception{Pending p=pending.poll(10,TimeUnit.SECONDS);check(p!=null,"checkpoint enqueued");return p;}
    }
    static final class Sink implements Consumer<RetroFrame> {
        final AtomicInteger frames=new AtomicInteger();final AtomicReference<RetroFrame> last=new AtomicReference<>();final Set<String> threads=ConcurrentHashMap.newKeySet();
        public void accept(RetroFrame f){threads.add(Thread.currentThread().getName());last.set(f);frames.incrementAndGet();}
    }
    static MdEngine start(Channel c,Sink s,boolean resume){return new MdEngine(rom,JNI,c,identity,resume,s);}
    static int hash(Sink s,MdEngine e,int p1,int p2)throws Exception{e.offerPort(0,p1);e.offerPort(1,p2);int before=s.frames.get();waitFor(()->s.frames.get()>=before+8,"fresh dual-port frames");return Arrays.hashCode(s.last.get().abgr());}
    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out.resolve("workspace"));RuntimeWorkspace.configure(out.resolve("workspace"));rom=out.resolve("two-port.md");
        byte[] bytes=MdRom.read(rom);identity=MdSaveCatalog.identity(NetplaySaveState.hash(bytes));
        // Direct real core confirms both physical ports, not merely two Java integers.
        try(var core=LibretroRuntimes.create(MdProfile.profile(),MdProfile.class,JNI)){
            core.load(bytes);var hashes=new HashSet<Integer>();
            for(int[] pair:new int[][]{{0,0},{1,0},{0,1},{1,1}}){LibretroProcess.Output f=null;for(int i=0;i<20;i++)f=core.run(List.of(new LibretroProcess.Controls(new int[]{MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,pair[0]),MdProfile.input(MdProfile.Core.GENESIS_PLUS_GX,pair[1])},0)),3);hashes.add(Arrays.hashCode(f.rgba()));}
            check(hashes.size()==4,"real six-button ports have four independent visual states");var ram=core.saveMemory().ram();
            check(ram[1]==0x5a&&ram[3]==(byte)0xa5,"both ports wrote separate battery locations");byte[] state=core.serialize();core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),0);core.restore(state);check(Arrays.equals(state,core.serialize()),"real GX state byte restore");
        }slots=NativeLibretroBridge.availableSlots();free();
        Disk disk=new Disk(null);Channel c=new Channel(disk,true);c.automatic=false;c.gateFinish=true;Sink sink=new Sink();MdEngine e=start(c,sink,true);ready(e);
        check(e.maxPlayers()==2&&e.canSave(),"public two-player save capability");
        Channel duplicate=new Channel(new Disk(null),true);boolean refused=false;try{start(duplicate,new Sink(),true);}catch(IllegalStateException expected){refused=true;}
        check(refused&&duplicate.aborts.get()==1,"busy engine aborts only the unused new persistence channel");
        int neutral=hash(sink,e,0,0),one=hash(sink,e,1,0),two=hash(sink,e,0,1),both=hash(sink,e,1,1);
        check(Set.of(neutral,one,two,both).size()==4,"independent engine port updates reach core");
        e.releasePort(0);int releaseBaseline=sink.frames.get();waitFor(()->sink.frames.get()>releaseBaseline+8,"release propagation");check(Arrays.hashCode(sink.last.get().abgr())==two,"returning 1P keeps 2P held");
        e.offerPort(1,0);e.offerPort(0,0);e.offerPort(0,1);e.offerPort(0,0);Thread.sleep(180);
        check(sink.last.get().pcm48k().length>0&&sink.last.get().pcm48k().length%2==0,"tap receives 48k stereo samples");
        RetroFrame tap=sink.last.get();Arrays.fill(tap.abgr(),0x12345678);var local=e.pollFrame();check(local!=null&&local.abgr()[0]!=0x12345678,"tap and local video arrays do not alias");
        var save=e.requestSave();Pending p=c.next();check(!save.isDone(),"save is not successful before durable ACK");
        int baseline=sink.frames.get();waitFor(()->sink.frames.get()>baseline+8,"game keeps advancing while save ACK is pending");
        c.ack(p);check(save.get(5,TimeUnit.SECONDS).saved(),"manual save succeeds after durable ACK");var prior=NetplaySaveState.decode(disk.bytes,identity);
        check(prior.ram()[1]==0x5a&&prior.ram()[3]==(byte)0xa5,"public checkpoint includes both SRAM values");
        e.paused(true);check(e.requestReset(),"public reset accepted while paused");Thread.sleep(100);var again=e.requestSave();Pending reset=c.next();
        check(NetplaySaveState.decode(reset.bytes,identity).frame()>prior.frame(),"RESET checkpoint sequence remains monotonic");c.ack(reset);check(again.get(5,TimeUnit.SECONDS).saved(),"paused reset can checkpoint");
        var stopped=e.stopAndSave();Pending last=c.next();check(!stopped.isDone()&&MdEngine.active()&&NativeLibretroBridge.availableSlots()==slots-1,"final commit retains owner/native slot");
        c.ack(last);waitFor(()->c.finishes.get()==1,"finish sent after final save ACK");check(!stopped.isDone()&&MdEngine.active(),"finish ACK also retains owner");
        c.finishAck.complete(null);check(stopped.get(5,TimeUnit.SECONDS).saved(),"stop succeeds only after final and finish ACK");free();
        check(c.threads.equals(Set.of("PIQ-MD-owner"))&&sink.threads.equals(Set.of("PIQ-MD-owner")),"load/save/finish and media callbacks are owner-thread only");
        Files.write(out.resolve("checkpoint.bin"),disk.bytes);
        Channel reopen=new Channel(disk,true);MdEngine resumed=start(reopen,new Sink(),true);ready(resumed);check(resumed.stopAndSave().get(8,TimeUnit.SECONDS).saved(),"server state and SRAM resume then save");
        var reopened=NetplaySaveState.decode(disk.bytes,identity);check(reopened.ram()[1]==0x5a&&reopened.ram()[3]==(byte)0xa5,"SRAM survives public restart");free();
        byte[] old=disk.bytes.clone();Channel disabled=new Channel(disk,false);MdEngine noSave=start(disabled,new Sink(),true);ready(noSave);check(!noSave.canSave()&&!noSave.requestSave().get().saved(),"no-save cannot manually persist");
        check(!noSave.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&disabled.pending.isEmpty()&&disabled.finishes.get()==0,"no-save never sends save or finish");check(Arrays.equals(old,disk.bytes),"no-save leaves old public checkpoint intact");free();
        // Failed ACK must not be claimed as saved or leak the native slot.
        Channel failed=new Channel(disk,true);failed.automatic=false;MdEngine fail=start(failed,new Sink(),true);ready(fail);var badSave=fail.requestSave();Pending bad=failed.next();bad.ack.completeExceptionally(new IllegalStateException("injected durable write failure"));
        waitFor(()->fail.error()!=null,"ACK failure becomes explicit engine failure");check(!fail.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&!badSave.get().saved(),"failed durable commit reports failure");check(Arrays.equals(old,disk.bytes),"failed commit retains last confirmed checkpoint");free();
        Channel corrupt=new Channel(new Disk(old.clone()),true);corrupt.disk.bytes[corrupt.disk.bytes.length-1]^=1;MdEngine broken=start(corrupt,new Sink(),true);waitFor(()->broken.error()!=null,"corrupt checkpoint fails startup");
        check(!broken.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&corrupt.pending.isEmpty(),"corrupt checkpoint is never overwritten");free();
        var oldParts=NetplaySaveState.decode(old,identity);Channel rtc=new Channel(new Disk(NetplaySaveState.encode(new NetplaySaveState.Parts(identity,oldParts.frame(),oldParts.state(),oldParts.ram(),new byte[]{1}))),true);
        MdEngine rtcEngine=start(rtc,new Sink(),true);waitFor(()->rtcEngine.error()!=null,"unsupported RTC fails closed");check(!rtcEngine.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&rtc.pending.isEmpty(),"RTC mismatch preserves source");free();
        Channel finalFailed=new Channel(new Disk(old.clone()),true);finalFailed.automatic=false;MdEngine failClose=start(finalFailed,new Sink(),true);ready(failClose);
        var failedStop=failClose.stopAndSave();Pending failedFinal=finalFailed.next();failedFinal.ack.completeExceptionally(new IllegalStateException("injected final commit failure"));
        check(!failedStop.get(8,TimeUnit.SECONDS).saved()&&finalFailed.finishes.get()==0,"failed final write cannot report saved or send FINISH");check(Arrays.equals(old,finalFailed.disk.bytes),"failed final write retains previous version");free();
        Channel finishFailed=new Channel(new Disk(old.clone()),true);finishFailed.gateFinish=true;MdEngine failFinish=start(finishFailed,new Sink(),true);ready(failFinish);
        var failedFinish=failFinish.stopAndSave();waitFor(()->finishFailed.finishes.get()==1,"finish failure follows confirmed final write");finishFailed.finishAck.completeExceptionally(new IllegalStateException("injected finish ACK failure"));
        check(!failedFinish.get(8,TimeUnit.SECONDS).saved(),"unconfirmed FINISH reports incomplete shutdown save acknowledgement");check(finishFailed.disk.bytes!=null,"confirmed checkpoint retained despite FINISH failure");free();
        Channel fresh=new Channel(disk,true);fresh.automatic=false;MdEngine fromStart=start(fresh,new Sink(),false);ready(fromStart);check(Arrays.equals(old,disk.bytes),"from-start preserves previous save before first successful commit");var freshStop=fromStart.stopAndSave();Pending freshPending=fresh.next();
        check(NetplaySaveState.decode(freshPending.bytes,identity).ram().length==0,"from-start does not import old battery");fresh.ack(freshPending);check(freshStop.get(8,TimeUnit.SECONDS).saved(),"explicit from-start persists after ACK");free();
        Channel cancelled=new Channel(new Disk(null),true);cancelled.gateLoad=true;MdEngine waiting=start(cancelled,new Sink(),true);waitFor(()->cancelled.loads.get()==1,"startup load waits on channel");
        check(!waiting.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&cancelled.aborts.get()>0,"startup cancel interrupts load and keeps save absent");free();
        Channel mismatch=new Channel(new Disk(null),true);MdEngine wrong=new MdEngine(rom,JNI,mismatch,MdSaveCatalog.identity("0".repeat(64)),true,f->{});waitFor(()->wrong.error()!=null,"ROM identity mismatch explicit");
        check(!wrong.stopAndSave().get(8,TimeUnit.SECONDS).saved()&&mismatch.loads.get()==0,"wrong ROM never reaches persistence load");free();
        check(!Files.exists(out.resolve("workspace/private-saves-v1")),"public sessions never create private progress");
        System.out.println("PASS checks="+checks+"; scope=real-GX/JNI public engine, fake transport; NOT MC integration or Netplay");
    }
}
