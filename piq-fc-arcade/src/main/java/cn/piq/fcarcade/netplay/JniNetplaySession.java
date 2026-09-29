// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.netplay.RollbackTimeline;
import cn.piq.retro.netplay.RollbackTimeline.Input;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;
import static cn.piq.fcarcade.netplay.JniNetplayCodec.*;

/**
 * Explicit FC digital-pad JNI trial. Native work, rollback and serialization have one owner.
 * The existing server relay, not a peer field, determines input lanes. No ROMs travel here.
 * A late join briefly pauses the room at a confirmed frame while transferring its seed.
 */
public final class JniNetplaySession implements AutoCloseable {
    private static final int PREDICTION=12, RETAIN=16;
    private static final long TIMEOUT=TimeUnit.SECONDS.toNanos(30);
    private final NetplayProcess.Grant grant;
    private final Callable<byte[]> content;
    private final Consumer<NetplayChunk> sender;
    private final Supplier<LibretroRuntime> factory;
    private final ArrayBlockingQueue<Event> events=new ArrayBlockingQueue<>(512);
    private final ArrayBlockingQueue<NetplayProcess.Frame> pictures=new ArrayBlockingQueue<>(3);
    private final Map<UUID,Peer> peers=new LinkedHashMap<>();
    private final NavigableMap<Long,Integer> futurePads=new TreeMap<>();
    private final NavigableMap<Long,Input> canonicalFuture=new TreeMap<>();
    private final NavigableMap<Long,String> digests=new TreeMap<>();
    private final NavigableMap<Long,String> pendingDigests=new TreeMap<>();
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    private final Object intakeLock=new Object();
    private int intakeBytes;
    private volatile boolean started,closing,closed,ready;
    private volatile int input;
    private volatile long delivered,replayed,confirmedVisible,rejectedPeers;
    private volatile String failure,phase="准备 FC JNI Netplay",saveStatus="不保存进度";
    private volatile NetplayProcess.Persistence persistence;
    private CompletableFuture<byte[]> capture;
    private CompletableFuture<Void> commit=CompletableFuture.completedFuture(null);
    private volatile LibretroRuntime core;
    private RollbackTimeline<LibretroProcess.Output> timeline;
    private NetplaySaveState.Identity identity;
    private long confirmed,lastHostNext,lastTraffic=System.nanoTime(),nextSave,lastSaved=-1;
    private int lastP1,lastP2;
    private Peer joining,remote;
    private AssemblyState incomingSeed;
    private long nextPresent;
    private double audioNext,audioSource;
    private float audioPrevious;
    private record Event(NetplayChunk chunk,int port) {}
    private static final class Peer {
        final UUID ticket;final int port;
        long rx,tx,deadline=System.nanoTime()+TIMEOUT,lastPad=-1,lastDigest=-1,lastInput=System.nanoTime();
        boolean hello,active;
        byte[] seed;
        int seedOffset;
        long seedFrame,sendAt;
        Peer(UUID ticket,int port){this.ticket=ticket;this.port=port;}
    }
    private static final class AssemblyState {
        final Seed offer;final NetplaySaveTransfer.Assembly assembly;
        AssemblyState(Seed offer){this.offer=offer;assembly=new NetplaySaveTransfer.Assembly(offer.bytes());}
    }
    public JniNetplaySession(NetplayProcess.Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender) {
        this(grant,content,sender,()->new LibretroJniRuntime(profile(),NetplayProcess.class));
    }
    /** Trusted test/core adapter injection; never supplied by network metadata. */
    JniNetplaySession(NetplayProcess.Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,Supplier<LibretroRuntime> factory) {
        this.grant=Objects.requireNonNull(grant);this.content=Objects.requireNonNull(content);
        this.sender=Objects.requireNonNull(sender);this.factory=Objects.requireNonNull(factory);
        if(grant.port()>1)throw new IllegalArgumentException("FC JNI trial has two digital pads");
    }
    public static LibretroProfile profile() {
        var p=cn.piq.fcarcade.core.libretro.GenericLibretroNesCore.profile(false);
        return new LibretroProfile(p.name(),p.extension(),p.fullPath(),p.devices(),false,p.options(),
                Map.of("windows-x64",new LibretroProfile.Artifact(
                        "/core/libretro-jni-netplay/windows-x64/mesen_piq_jni_netplay_r1.dll",
                        "81989a6d9932c928a9a75b63ae7100381b99529ddba3064d0d92d66c2162aabe")));
    }
    public synchronized void persistence(NetplayProcess.Persistence value) {
        if(started||closed||!grant.host())throw new IllegalStateException("主持启动前绑定保存");
        persistence=Objects.requireNonNull(value);saveStatus="等待 JNI 试验存档";
    }
    public synchronized void start() {
        if(started||closed)return;started=true;
        var thread=new Thread(this::run,"PIQ-FC-JNI-Netplay-owner");thread.setDaemon(true);thread.start();
    }
    public void input(int value){input=closing||closed||grant.port()<0?0:value&65535;}
    public boolean ready(){return ready&&!closing&&!closed;}
    public String error(){var c=core;String nativeError=c==null?"":c.diagnosticError();return failure!=null?failure:nativeError.isBlank()?null:nativeError;}
    public String status(){return error()!=null?"JNI Netplay 停止："+error():closed?"已结束":phase;}
    public long framesReceived(){return delivered;}
    public long replayedFrames(){return replayed;}
    public NetplayProcess.Frame poll(){return pictures.poll();}
    public String saveStatus(){return saveStatus;}
    public boolean canSave(){return grant.host()&&ready()&&persistence!=null&&persistence.enabled();}
    public CompletableFuture<Void> terminated(){return terminated;}
    public String diagnostic(){return status()+"\nJNI 试验；确认帧："+confirmedVisible+"；呈现帧："+delivered+"；重演帧："+replayed+"；拒绝异常连接："+rejectedPeers+"\n存档："+saveStatus;}
    public synchronized CompletableFuture<byte[]> checkpoint() {
        if(!grant.host()||!ready||closed)return CompletableFuture.failedFuture(new IllegalStateException("JNI 主持未就绪"));
        if(capture==null||capture.isDone())capture=new CompletableFuture<byte[]>().orTimeout(15,TimeUnit.SECONDS);
        return capture;
    }
    public synchronized CompletableFuture<Void> saveNow() {
        if(persistence==null)return CompletableFuture.failedFuture(new IllegalStateException("本局未启用保存"));
        if(!commit.isDone())return commit;
        saveStatus="正在保存 JNI 试验进度…";
        commit=checkpoint().thenCompose(persistence::save);
        commit.whenComplete((unused,error)->saveStatus=error==null?"JNI 试验进度已保存到服务器":"保存未确认，保留最近确认版本");
        return commit;
    }
    /** Network thread does bounded copying/enqueue only; no native work or server-side authority expansion. */
    public void receive(NetplayChunk chunk,int authorizedPort) {
        if(closed||closing||chunk.session()!=grant.session()||authorizedPort< -1||authorizedPort>1)return;
        if(!grant.host()&&!chunk.ticket().equals(grant.ticket()))return;
        synchronized(intakeLock) {
            if(chunk.byteLength()>2*1024*1024-intakeBytes||!events.offer(new Event(chunk,authorizedPort))) {
                failure="JNI Netplay 接收队列超限，已停止";closing=true;return;
            }
            intakeBytes+=chunk.byteLength();
        }
    }
    @Override public synchronized void close() {
        closing=true;input=0;
        if(!started){closed=true;terminated.complete(null);}
    }
    private void run() {
        try {
            byte[] rom=content.call();if(rom==null||rom.length<16||rom.length>NetplayProfile.fc().maxRomBytes())throw new IOException("FC ROM 大小异常");
            identity=FcNetplaySaves.jniIdentity(NetplaySaveState.hash(rom));
            byte[] saved=null;
            if(persistence!=null){saved=persistence.load(identity);if(saved!=null)NetplaySaveState.decode(saved,identity);if(!persistence.enabled())persistence=null;}
            if(closing)return;
            core=factory.get();var info=core.load(rom);
            if(info.width()!=256||info.height()!=240||info.fps()<59||info.fps()>61||core.rotation()!=0
                    ||!core.capabilities().contains(LibretroRuntime.Capability.STATE))throw new IOException("本期只支持 NTSC FC 数字手柄及可回滚核心");
            // Probe before networking. Restoring a snapshot must round-trip with no side effects.
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),0);core.reset();
            // Mesen queues reset until retro_run. Drain it before restoring a room seed;
            // serializing immediately after reset can round-trip yet reset on the next frame.
            core.run(List.of(new LibretroProcess.Controls(new int[]{0,0},0)),0);
            byte[] probe=core.serialize();core.restore(probe);
            if(probe.length>RollbackTimeline.MAX_STATE||!Arrays.equals(probe,core.serialize()))throw new IOException("核心不能精确恢复，未开始 JNI Netplay");
            long frame=0;
            if(saved!=null){var parts=NetplaySaveState.decode(saved,identity);restore(parts);frame=parts.frame();}
            timeline=newTimeline(frame);confirmed=lastHostNext=frame;
            nextSave=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            nextPresent=System.nanoTime();
            if(grant.host()){ready=true;phase="FC JNI Netplay（试验）";}
            else{remote=new Peer(grant.ticket(),grant.port());peers.put(remote.ticket,remote);control(remote,NetplayChunk.OPEN);phase="等待 JNI 主持与种子状态";}
            while(!closing) {
                drain();expire();
                if(error()!=null)throw new IOException(error());
                long now=System.nanoTime();
                if(grant.host()) {
                    confirm();prepareJoin();sendSeed();
                    boolean waitingJoin=peers.values().stream().anyMatch(p->p.hello&&!p.active);
                    if(joining==null&&!waitingJoin&&timeline.next()-confirmed<PREDICTION&&now>=nextPresent)hostFrame();
                    handleCapture();
                    if(persistence!=null&&now>=nextSave){nextSave=now+TimeUnit.SECONDS.toNanos(30);saveNow();}
                } else if(ready){if(now>=nextPresent)peerFrame();checkDigests();}
                if(grant.host()||ready)trim();
                confirmedVisible=confirmed;replayed=timeline.replayedFrames();
                LockSupport.parkNanos(500_000L);
            }
            if(grant.host()&&failure==null&&ready&&persistence!=null)finishSave();
        } catch(Exception|LinkageError error) {
            if(failure==null)failure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        } finally {
            ready=false;closing=true;
            try{sender.accept(new NetplayChunk(grant.session(),grant.ticket(),NetplayChunk.CLOSE,0,new byte[0]));}catch(RuntimeException ignored){}
            synchronized(this){if(capture!=null&&!capture.isDone())capture.completeExceptionally(new IOException("JNI 运行已结束，未取得新快照"));}
            if(persistence!=null)persistence.abort();
            try{if(core!=null)core.close();closed=true;terminated.complete(null);}
            catch(RuntimeException|LinkageError error){failure="JNI 核心关闭未确认；须正常重启客户端";terminated.completeExceptionally(error);}
            pictures.clear();events.clear();
        }
    }
    private RollbackTimeline<LibretroProcess.Output> newTimeline(long frame) {
        return new RollbackTimeline<>(new RollbackTimeline.Core<>() {
            public byte[] save(){return core.serialize();}
            public void restore(byte[] state){core.restore(state);audioSource=audioNext=0;audioPrevious=0;}
            public LibretroProcess.Output step(int a,int b,boolean present){return core.run(List.of(new LibretroProcess.Controls(new int[]{a,b},0)),present?3:0);}
        },frame);
    }
    private void drain() {
        for(int n=0;n<128;n++) {
            Event event=events.poll();if(event==null)return;
            synchronized(intakeLock){intakeBytes-=event.chunk().byteLength();}
            var chunk=event.chunk();var peer=peers.get(chunk.ticket());
            if(chunk.kind()==NetplayChunk.CLOSE){if(peer!=null)drop(peer,false);continue;}
            if(grant.host()&&chunk.kind()==NetplayChunk.OPEN) {
                if(peer==null&&peers.size()<8&&event.port()!=0){peer=new Peer(chunk.ticket(),event.port());peers.put(peer.ticket,peer);control(peer,NetplayChunk.ACK);}continue;
            }
            if(peer==null)continue;
            if(!grant.host()&&chunk.kind()==NetplayChunk.ACK&&!peer.hello){peer.hello=true;send(peer,new Hello(identity));continue;}
            try {
                if(event.port()!=peer.port||chunk.kind()!=NetplayChunk.DATA||chunk.sequence()!=peer.rx++)throw new IllegalArgumentException("JNI Netplay 包序列或授权席位异常");
                var message=decode(chunk.bytes());
                if(grant.host())hostMessage(peer,message);else peerMessage(peer,message);
                lastTraffic=System.nanoTime();
            } catch(IllegalArgumentException invalidPeer) {
                // A bad spectator/controller must not terminate the host or overwrite its good save.
                // A client cannot continue without a valid authority, so its host failure still stops it.
                if(!grant.host())throw invalidPeer;
                rejectedPeers++;drop(peer,true);
            }
        }
    }
    private void hostMessage(Peer peer,Message message) {
        if(message instanceof Hello hello&&!peer.hello) {
            if(!identity.equals(hello.identity())){drop(peer,true);return;}
            peer.hello=true;return;
        }
        if(message instanceof Ready value&&joining==peer&&peer.seed!=null&&peer.seedOffset==peer.seed.length&&value.frame()==peer.seedFrame) {
            joining=null;peer.seed=null;peer.active=true;peer.deadline=Long.MAX_VALUE;peer.lastInput=System.nanoTime();
            if(peer.port==1){if(remote!=null)throw new IllegalArgumentException("重复 2P 授权");remote=peer;futurePads.clear();lastP2=0;}
            nextPresent=System.nanoTime();return;
        }
        if(message instanceof Pad value&&peer.active&&peer==remote&&peer.port==1) {
            long frame=value.frame();if(frame!=(peer.lastPad>=0?peer.lastPad+1:peer.seedFrame)||frame>timeline.next()+PREDICTION)throw new IllegalArgumentException("2P 输入帧越界或重放");
            peer.lastPad=frame;peer.lastInput=System.nanoTime();
            if(frame<timeline.next()) {timeline.supply(frame,1,value.mask());lastP2=timeline.input(timeline.next()-1).p2();confirm();broadcastCommands();}
            else {if(futurePads.size()>=PREDICTION+1||futurePads.putIfAbsent(frame,value.mask())!=null)throw new IllegalArgumentException("2P 预输入超限");}
            return;
        }
        if(message instanceof Digest value&&peer.active) {
            String expected=digests.get(value.frame());
            if(value.frame()<=peer.lastDigest||expected==null||!expected.equals(value.sha()))throw new IllegalArgumentException("JNI Netplay 确认状态 CRC 不一致，未继续覆盖存档");
            peer.lastDigest=value.frame();return;
        }
        throw new IllegalArgumentException("JNI Netplay 当前阶段不接受此消息");
    }
    private void peerMessage(Peer peer,Message message) {
        if(message instanceof Seed seed&&!ready&&incomingSeed==null&&seed.port()==grant.port()) {
            incomingSeed=new AssemblyState(seed);return;
        }
        if(message instanceof Part part&&!ready&&incomingSeed!=null){incomingSeed.assembly.append(part.offset(),part.data());return;}
        if(message instanceof End&&!ready&&incomingSeed!=null) {
            var parts=NetplaySaveState.decode(unpack(incomingSeed.assembly.finish()),identity);
            if(parts.frame()!=incomingSeed.offer.frame())throw new IllegalArgumentException("种子帧号不一致");
            restore(parts);timeline=newTimeline(parts.frame());confirmed=lastHostNext=parts.frame();
            incomingSeed=null;ready=true;peer.active=true;peer.deadline=Long.MAX_VALUE;
            phase="FC JNI Netplay（试验）";send(peer,new Ready(parts.frame()));nextPresent=System.nanoTime();return;
        }
        if(message instanceof Commands commands&&ready) {
            if(commands.confirmed()<confirmed||commands.next()<lastHostNext||commands.next()>timeline.next()+32)throw new IllegalArgumentException("主持时间线倒退或超限");
            var corrections=new ArrayList<Input>();
            for(var command:commands.inputs()) {
                if(command.frame()<timeline.oldest())continue;
                if(command.frame()<timeline.next()) {
                    var local=timeline.input(command.frame());
                    if(grant.port()==1) {
                        if((command.known()&2)!=0&&local.p2()!=command.p2())throw new IllegalArgumentException("主持更改已提交的 2P 输入");
                        if((command.known()&2)==0)command=new Input(command.frame(),command.p1(),local.p2(),command.known()|2);
                    }
                    corrections.add(command);
                } else canonicalFuture.put(command.frame(),command);
            }
            if(canonicalFuture.size()>32)throw new IllegalArgumentException("主持预输入超限");
            if(!corrections.isEmpty())timeline.canonical(corrections);
            confirmed=commands.confirmed();lastHostNext=commands.next();
            if(timeline.next()>timeline.oldest()){var latest=timeline.input(timeline.next()-1);lastP1=latest.p1();lastP2=latest.p2();}
            return;
        }
        if(message instanceof Digest digest&&ready) {
            if(digest.frame()>confirmed||digest.frame()<timeline.oldest()||pendingDigests.size()>=8
                    ||pendingDigests.putIfAbsent(digest.frame(),digest.sha())!=null)throw new IllegalArgumentException("CRC 不在确认窗口或重复");
            checkDigests();return;
        }
        throw new IllegalArgumentException("JNI Netplay 接收阶段异常");
    }
    private void hostFrame() {
        Integer received=futurePads.remove(timeline.next());int known=1;
        if(remote==null){lastP2=0;known=3;}else if(received!=null){lastP2=received;known=3;}
        lastP1=input;
        publish(timeline.advance(lastP1,lastP2,known));confirm();broadcastCommands();pace();
    }
    private void peerFrame() {
        long frame=timeline.next();Input canonical=canonicalFuture.get(frame);
        if(grant.port()<0&&canonical==null||grant.port()==1&&frame>=lastHostNext+2)return;
        canonicalFuture.remove(frame);
        int a=canonical==null?lastP1:canonical.p1(),b=grant.port()==1?input:canonical.p2();
        int known=canonical==null?0:canonical.known();
        if(grant.port()==1){if(canonical!=null&&(known&2)!=0&&canonical.p2()!=b)throw new IllegalArgumentException("2P 提交前帧已被确认");known|=2;send(remote,new Pad(frame,b));}
        lastP1=a;lastP2=b;publish(timeline.advance(a,b,known));pace();
    }
    private void checkDigests() {
        while(!pendingDigests.isEmpty()&&pendingDigests.firstKey()<=timeline.next()) {
            var digest=pendingDigests.pollFirstEntry();
            String actual=NetplaySaveState.hash(timeline.stateBefore(digest.getKey()));
            if(!actual.equals(digest.getValue()))throw new IllegalArgumentException("JNI Netplay 确认状态 CRC 不一致");
            send(remote,new Digest(digest.getKey(),actual));
        }
    }
    private void confirm() {while(confirmed<timeline.next()&&timeline.input(confirmed).known()==3)confirmed++;}
    private void broadcastCommands() {
        if(timeline.next()==timeline.oldest())return;
        var commands=new Commands(confirmed,timeline.next(),timeline.inputs(timeline.oldest(),timeline.next()));
        for(var p:peers.values())if(p.active)send(p,commands);
        // Confirmed state hashes are independent of video/audio. Retain bounded history for ACKs.
        long frame=confirmed-confirmed%60;
        if(frame>=timeline.oldest()&&frame>0&&!digests.containsKey(frame)) {
            String hash=NetplaySaveState.hash(timeline.stateBefore(frame));digests.put(frame,hash);
            while(digests.size()>64)digests.pollFirstEntry();
            for(var p:peers.values())if(p.active&&p.seedFrame<frame)send(p,new Digest(frame,hash));
        }
    }
    private void prepareJoin()throws IOException {
        if(joining!=null||confirmed!=timeline.next())return;
        for(var p:peers.values())if(p.hello&&!p.active) {
            if(p.port==1&&remote!=null){drop(p,true);return;}
            byte[] seed=NetplaySaveTransfer.pack(checkpointAt(confirmed));
            if(seed.length>SEED_LIMIT)throw new IOException("FC JNI 种子状态超过 4 MiB");
            joining=p;p.seed=seed;p.seedFrame=confirmed;p.deadline=System.nanoTime()+TIMEOUT;
            send(p,new Seed(seed.length,confirmed,p.port));return;
        }
    }
    private void sendSeed() {
        var p=joining;if(p==null||p.seedOffset==p.seed.length||System.nanoTime()<p.sendAt)return;
        int end=Math.min(p.seed.length,p.seedOffset+PART);
        send(p,new Part(p.seedOffset,Arrays.copyOfRange(p.seed,p.seedOffset,end)));
        p.seedOffset=end;p.sendAt=System.nanoTime()+25_000_000L; // <=640 kB/s, shares existing relay budget.
        if(end==p.seed.length)send(p,new End());
    }
    private void expire() {
        long now=System.nanoTime();
        for(var p:List.copyOf(peers.values()))if(now>p.deadline)drop(p,true);
        if(!grant.host()&&now-lastTraffic>TIMEOUT)throw new IllegalStateException("JNI 主持超时，请重新加入");
        if(grant.host()&&remote!=null&&now-remote.lastInput>TIMEOUT)drop(remote,true);
    }
    private void drop(Peer peer,boolean notify) {
        peers.remove(peer.ticket);if(joining==peer)joining=null;
        if(grant.host()&&remote==peer) {
            // Disconnection finalizes only already simulated predictions; future input is neutral.
            for(long f=confirmed;f<timeline.next();f++)if((timeline.input(f).known()&2)==0)timeline.supply(f,1,timeline.input(f).p2());
            remote=null;futurePads.clear();lastP2=0;confirm();broadcastCommands();
        }
        if(notify)control(peer,NetplayChunk.CLOSE);
        if(!grant.host()){failure="JNI Netplay 连接已结束，请重新加入";closing=true;}
    }
    private void trim() {long before=Math.max(timeline.oldest(),Math.min(confirmed,timeline.next())-RETAIN);timeline.discardBefore(before);}
    private void pace(){long now=System.nanoTime();long interval=(long)(1_000_000_000d/core.info().fps());nextPresent=Math.max(nextPresent+interval,now-interval);}
    private void publish(LibretroProcess.Output output) {
        if(output.rgba().length!=256*240*4)throw new IllegalStateException("FC JNI 画面大小异常");
        float[] mono=mono44100(output.stereo(),output.info().sampleRate());
        var picture=new NetplayProcess.Frame(timeline.next(),output.rgba(),mono,256,240,output.info().aspect(),new short[0],44100,0);
        while(!pictures.offer(picture))pictures.poll();delivered++;
    }
    private float[] mono44100(short[] samples,double rate) {
        if(samples.length>32768||(samples.length&1)!=0)throw new IllegalArgumentException("音频上限");
        var buffer=new float[16384];int count=0;double step=rate/44100d;
        for(int i=0;i<samples.length;i+=2) {
            float current=(samples[i]+samples[i+1])/65536f;
            while(audioNext<=audioSource) {
                if(count==buffer.length)throw new IllegalArgumentException("音频重采样上限");
                double fraction=audioSource==0?1:Math.clamp(audioNext-(audioSource-1),0,1);
                buffer[count++]=(float)(audioPrevious+(current-audioPrevious)*fraction);audioNext+=step;
            }
            audioPrevious=current;audioSource++;
        }
        return Arrays.copyOf(buffer,count);
    }
    private byte[] checkpointAt(long frame) {
        byte[] state=timeline.stateBefore(frame),current=core.serialize();
        try {core.restore(state);var memory=core.saveMemory();return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,frame,core.serialize(),memory.ram(),memory.rtc()));}
        finally {core.restore(current);}
    }
    private synchronized void handleCapture() {
        if(capture==null||capture.isDone()||confirmed<=lastSaved)return;
        try {byte[] state=checkpointAt(confirmed);lastSaved=confirmed;capture.complete(state);}
        catch(RuntimeException error){capture.completeExceptionally(error);}
    }
    private void restore(NetplaySaveState.Parts parts) {
        core.restoreSaveMemory(new LibretroSaveMemory(parts.ram(),parts.rtc()));core.restore(parts.state());
        if(!Arrays.equals(parts.state(),core.serialize()))throw new IllegalArgumentException("JNI 种子/存档恢复不精确");
    }
    private void finishSave() {
        try {
            // Stop gameplay, then finish the same authorized host transaction off the MC thread.
            for(var p:List.copyOf(peers.values()))drop(p,false);
            try{commit.get(30,TimeUnit.SECONDS);}catch(ExecutionException ignored){}
            timeline.advance(0,0,3);confirm();
            byte[] state=checkpointAt(confirmed);persistence.save(state).get(30,TimeUnit.SECONDS);
            persistence.finish().get(10,TimeUnit.SECONDS);saveStatus="最终 JNI 试验进度已保存";
        } catch(Exception error) {saveStatus="最终保存未确认，保留最近确认版本";}
    }
    private static byte[] unpack(byte[] bytes) {try{return NetplaySaveTransfer.unpack(bytes);}catch(IOException e){throw new IllegalArgumentException("种子压缩损坏",e);}}
    private void send(Peer peer,Message message){sender.accept(new NetplayChunk(grant.session(),peer.ticket,NetplayChunk.DATA,peer.tx++,encode(message)));}
    private void control(Peer peer,int kind){sender.accept(new NetplayChunk(grant.session(),peer.ticket,kind,0,new byte[0]));}
}
