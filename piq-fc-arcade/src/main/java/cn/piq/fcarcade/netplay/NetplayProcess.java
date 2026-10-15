// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.netplay;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import cn.piq.fcarcade.retroarch.RetroArchSession;
import cn.piq.retro.libretro.LibretroContentFiles;

/** RetroArch owns pacing/rollback. No core or blocking I/O on the Minecraft thread.
 * Native sockets are loopback; caller owns authenticated Minecraft transport.
 * Persistence is opt-in and host-only. The caller owns authorization and durable storage.
 */
public final class NetplayProcess implements AutoCloseable {
    private final long replayEpoch=cn.piq.retro.replay.ReplayGuard.epoch();
    private volatile boolean discardSave;
    private boolean replayValid(){return cn.piq.retro.replay.ReplayGuard.valid(replayEpoch)&&!discardSave;}
    /** Trusted frontend identity, shared with server-selected launch contracts. */
    public static final String RETROARCH_SHA="1335c34b04d8c3e55afe010a421ff373d938929ec196a6e93a9657972fe034e1";
    public static final String RETROARCH_PROTOCOL="PNP7";
    public enum Role { HOST, PARTICIPANT, SPECTATOR, LOCAL }
    private JniNetplaySession jni;
    public record Frame(long number, byte[] rgba, float[] mono,int width,int height,float aspect,short[] stereo,int sampleRate,int rotation) {
        public Frame(long number,byte[] rgba,float[] mono,int width,int height,float aspect,short[] stereo,int sampleRate){this(number,rgba,mono,width,height,aspect,stereo,sampleRate,0);}
    }
    public record Grant(long session, UUID ticket, boolean host, boolean player,int port) {
        public Grant(long session,UUID ticket,boolean host,boolean player){this(session,ticket,host,player,host?0:player?1:-1);}
        public Grant {if(session<0||ticket==null||port< -1||port>3||host&&port!=0||!host&&player!=(port>=0)||!host&&port==0)throw new IllegalArgumentException("Grant");}
    }
    private static final InetAddress LOOP=loopback();
    private static final Set<Process> CHILDREN=ConcurrentHashMap.newKeySet();
    static {Runtime.getRuntime().addShutdownHook(new Thread(()->CHILDREN.forEach(Process::destroyForcibly),"PIQ-Netplay-shutdown"));}
    private final Grant grant;
    private final Role role;
    private final RetroArchSession.Config localConfig;
    private final Callable<LibretroContentFiles> localFiles;
    private LibretroContentFiles verifiedFiles;
    private final AtomicBoolean localCloseRequested=new AtomicBoolean();
    private Supplier<int[]> localInputProvider;
    private final NetplayProfile profile;
    private final boolean gunMode;
    private final boolean cabinetAuthority;
    private final NetplayCabinetInputs cabinetInputs;
    private final Callable<Map<String,byte[]>> auxiliary;
    private final Callable<byte[]> content;
    private final Consumer<NetplayChunk> sender;
    private final String token=UUID.randomUUID().toString().replace("-","");
    private final Map<UUID,Pipe> pipes=new ConcurrentHashMap<>();
    private final NetplaySockets sockets=new NetplaySockets();
    private final BlockingQueue<Frame> frames=new ArrayBlockingQueue<>(6);
    private final CountDownLatch connected=new CountDownLatch(1);
    private volatile boolean closed,ready;
    private volatile int input;
    private final NetplayGunMailbox gunInputs=new NetplayGunMailbox();
    private volatile String error;
    private volatile Process child;
    private volatile boolean forcedTermination;
    private volatile Socket ipc;
    private volatile ServerSocket ipcListener,proxyListener;
    private volatile int nativePort;
    private Path directory;
    private cn.piq.retro.storage.RuntimeWorkspace workspace;
    private final RetroArchProcessLog processLog=new RetroArchProcessLog(token);
    private volatile long framesReceived;
    private volatile String phase="等待启动";
    public interface Persistence {
        /** Runs on the background owner before native startup. Null means an authorized new game. */
        byte[] load(NetplaySaveState.Identity identity) throws Exception;
        default boolean enabled(){return true;}
        /** Must return promptly; complete only after durable commit, never after merely queuing an upload. */
        CompletableFuture<Void> save(byte[] checkpoint);
        default CompletableFuture<Void> finish(){return CompletableFuture.completedFuture(null);}
        default void abort(){}
    }
    private volatile Persistence persistence;
    public synchronized void operatingFiles(JniNetplaySession.OperatingFiles value){if(jni==null)throw new IllegalStateException("Operating files are not supported by this backend");jni.operatingFiles(value);}
    private NetplaySaveState.Identity saveIdentity;
    private byte[] initialSave;
    private volatile boolean closing;
    private volatile String saveStatus="不保存进度";
    private int saveRequest,saveSent;
    private CompletableFuture<byte[]> capture;
    private volatile CompletableFuture<Void> commit=CompletableFuture.completedFuture(null);
    private long nextSaveNanos;
    private final CompletableFuture<Void> terminated=new CompletableFuture<>();
    private final CompletableFuture<Void> transportTerminated=new CompletableFuture<>();
    /** Call before start, never on a participant or observer. */
    public synchronized void persistence(Persistence value){
        if(jni!=null){jni.persistence(value);return;}
        if(started||closed||localCloseRequested.get()||!ownsPersistence())throw new IllegalStateException("只有未启动的主持或 LOCAL 会话可绑定存档");
        persistence=Objects.requireNonNull(value);saveStatus=role==Role.LOCAL?"等待本局存档":"等待服务器存档";
    }
    public String saveStatus(){return jni!=null?jni.saveStatus():saveStatus;}
    public boolean canSave(){if(jni!=null)return jni.canSave();var p=persistence;return ownsPersistence()&&ready&&!closed&&!closing&&p!=null&&p.enabled();}
    public String saveLabel(){return (jni!=null?"JNI · ":"")+profile.contentName()+" · "+(role==Role.LOCAL?"RetroArch LOCAL":grant.host()?"主持":"参与 / 旁观");}
    public CompletableFuture<Void> terminated(){return jni!=null?jni.terminated():terminated;}
    public boolean nativeSlotHeld(){return jni!=null&&jni.nativeSlotHeld();}
    public Role role(){return role;}
    public boolean forcedTermination(){return forcedTermination;}
    private boolean ownsPersistence(){return role==Role.LOCAL||grant.host();}
    public synchronized CompletableFuture<byte[]> checkpoint(){
        if(!replayValid())return CompletableFuture.failedFuture(new IllegalStateException("Replay retired this runtime"));
        if(jni!=null)return jni.checkpoint();
        if(!ownsPersistence()||!ready||closed)return CompletableFuture.failedFuture(new IllegalStateException("保存会话尚未就绪"));
        if(capture!=null&&!capture.isDone())return capture;
        if(saveRequest==Integer.MAX_VALUE)return CompletableFuture.failedFuture(new IllegalStateException("保存序号已耗尽"));
        saveRequest++;capture=new CompletableFuture<>();
        capture.orTimeout(15,TimeUnit.SECONDS).whenComplete((bytes,failure)->{if(failure instanceof TimeoutException){fail("核心捕获存档超时，已停止；保留最近确认的存档");terminate();}});
        return capture;
    }
    /** Safe menu action: host-only, serialized commits, no runtime load/rewind of a live room. */
    public synchronized CompletableFuture<Void> saveNow(){
        if(!replayValid())return CompletableFuture.failedFuture(new IllegalStateException("Replay retired this runtime"));
        if(jni!=null)return jni.saveNow();
        if(persistence==null)return CompletableFuture.failedFuture(new IllegalStateException("本局没有启用保存"));
        if(!commit.isDone())return commit;
        saveStatus="正在保存…";
        commit=checkpoint().thenCompose(bytes->replayValid()?persistence.save(bytes):CompletableFuture.failedFuture(new IllegalStateException("Replay retired this save")));
        commit.whenComplete((unused,failure)->saveStatus=failure==null?(role==Role.LOCAL?"本局存档已确认":"已保存到服务器"):"保存未确认；请保留已有存档备份");
        return commit;
    }
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender) {
        this(grant,content,sender,NetplayProfile.fc(),Map::of);
    }
    /** FC JNI route. Caller applies the local backend policy before true. */
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,boolean confirmedJniTrial) {
        this(grant,content,sender,confirmedJniTrial,false);
    }
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,boolean confirmedJniTrial,boolean gun) {
        this(grant,content,sender,gun?NetplayProfile.fcZapper():NetplayProfile.fc(),Map::of);
        if(confirmedJniTrial)jni=new JniNetplaySession(grant,content,sender,gun);
    }
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,NetplayProfile profile,Callable<Map<String,byte[]>> auxiliary) {
        this(grant,content,sender,profile,auxiliary,false);
    }
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,NetplayProfile profile,Callable<Map<String,byte[]>> auxiliary,boolean cabinetAuthority) {
        this(grant,content,sender,profile,auxiliary,cabinetAuthority,true);
    }
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,NetplayProfile profile,Callable<Map<String,byte[]>> auxiliary,boolean cabinetAuthority,boolean paid) {
        this(grant,content,sender,profile,auxiliary,cabinetAuthority,paid,false);
    }
    /** JNI hosts may prepare/restore first; the caller activates only after authoritative READY. */
    public NetplayProcess(Grant grant,Callable<byte[]> content,Consumer<NetplayChunk> sender,NetplayProfile profile,Callable<Map<String,byte[]>> auxiliary,boolean cabinetAuthority,boolean paid,boolean waitForActivation) {
        this.cabinetInputs=new NetplayCabinetInputs(paid);
        this.grant=Objects.requireNonNull(grant);this.content=Objects.requireNonNull(content);Objects.requireNonNull(sender);this.sender=chunk->{if(replayValid())sender.accept(chunk);};
        this.role=grant.host()?Role.HOST:grant.player()?Role.PARTICIPANT:Role.SPECTATOR;
        this.localConfig=null;this.localFiles=null;
        this.profile=Objects.requireNonNull(profile);this.gunMode=profile.isFcZapper();this.auxiliary=Objects.requireNonNull(auxiliary);
        this.cabinetAuthority=cabinetAuthority;if(cabinetAuthority&&gunMode)throw new IllegalArgumentException("Cabinet gun authority");
        if(grant.port()>=profile.ports())throw new IllegalArgumentException("Port not supported by core profile");
        if(waitForActivation&&(profile.jni()==null||!grant.host()))throw new IllegalArgumentException("Prepared activation requires a JNI host");
        if(profile.jni()!=null) {
            jni=new JniNetplaySession(grant,content,sender,profile,auxiliary,cabinetAuthority?cabinetInputs:null,waitForActivation);
        }
    }
    /** LOCAL has no server grant, transport callback, peer listener or JNI fallback. */
    private NetplayProcess(RetroArchSession.Config config,Callable<byte[]> content,
                           Callable<Map<String,byte[]>> auxiliary,Callable<LibretroContentFiles> files){
        localConfig=Objects.requireNonNull(config);profile=config.profile();role=Role.LOCAL;
        if(profile.jni()!=null)throw new IllegalArgumentException("LOCAL cannot use a JNI profile");
        grant=null;sender=null;this.content=content;this.auxiliary=auxiliary;localFiles=files;
        gunMode=false;cabinetAuthority=true;cabinetInputs=new NetplayCabinetInputs(false);
    }
    public static NetplayProcess local(RetroArchSession.Config config,Callable<LibretroContentFiles> files){
        return new NetplayProcess(config,null,null,Objects.requireNonNull(files));
    }
    public static NetplayProcess local(RetroArchSession.Config config,Callable<byte[]> content,Callable<Map<String,byte[]>> auxiliary){
        return new NetplayProcess(config,Objects.requireNonNull(content),Objects.requireNonNull(auxiliary),null);
    }
    public synchronized void localInputProvider(Supplier<int[]> provider){
        requireLocal();if(started||closed||localCloseRequested.get())throw new IllegalStateException("Bind LOCAL inputs before start");
        localInputProvider=Objects.requireNonNull(provider);
    }
    public void localInput(int port,int mask){
        requireLocal();checkAuthorityPort(port);if((mask&~4095)!=0)throw new IllegalArgumentException("LOCAL pad mask");
        if(!closed&&!closing&&!localCloseRequested.get())cabinetInputs.input(port,mask,System.nanoTime());
    }
    public void localRelease(int port){requireLocal();checkAuthorityPort(port);cabinetInputs.release(port);}
    private void requireLocal(){if(role!=Role.LOCAL)throw new IllegalStateException("Not a LOCAL session");}
    private boolean started;
    /** Bind captured files before start. Hashing/staging stays on the runtime owner thread. */
    public synchronized void fileContent(cn.piq.retro.libretro.LibretroContentFiles files){
        if(started||closed||localCloseRequested.get()||role==Role.LOCAL)
            throw new IllegalStateException("Verified files require an unstarted Netplay process");
        Objects.requireNonNull(files);
        if(jni!=null)jni.fileContent(files);else verifiedFiles=files;
    }
    public synchronized void start(){if(!replayValid()){closeWithoutSave();return;}if(started||closed||localCloseRequested.get())return;started=true;if(jni!=null){jni.start();return;}daemon("PIQ-Netplay-owner",this::run);}
    public static String unavailableReason() {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch","");
        return os.startsWith("windows")&&(arch.equals("amd64")||arch.equals("x86_64"))?null:"Netplay 实验仅支持 Windows x64 客户端";
    }
    public boolean ready(){return jni!=null?jni.ready():ready&&!closed;}
    public void activate(){if(jni==null)throw new IllegalStateException("Prepared activation requires JNI");jni.activate();}
    public String error(){return jni!=null?jni.error():error;}
    /** Presentation only, never an authority/handshake decision. ready() still means local AV bridge ready. */
    public String status(){return jni!=null?jni.status():error!=null?"已停止："+error:closed?(terminated.isDone()?"已结束":"正在关闭，等待子进程回收"):closing?(persistence==null?"正在关闭":"正在确认最后保存"):phase;}
    private synchronized void phase(String value){if(!closed)phase=value;}
    private synchronized void fail(String reason){if(!closed&&error==null)error=reason;}
    public long framesReceived(){return jni!=null?jni.framesReceived():framesReceived;}
    public Grant grant(){if(role==Role.LOCAL)throw new IllegalStateException("LOCAL has no network grant");return grant;}
    /** PNP7's libretro CCW metadata becomes CW once, before any public frame consumer. */
    static int legacyClockwiseRotation(int counterClockwise){
        if(counterClockwise<0||counterClockwise>3)throw new IllegalArgumentException("Legacy rotation");
        return (4-counterClockwise)&3;
    }
    public Frame poll(){return jni!=null?jni.poll():frames.poll();}
    public void input(int nes){if(role==Role.LOCAL){localInput(0,retroPad(nes));return;}if(jni!=null){jni.input(retroPad(nes));return;}if(!gunMode)input=grant.player()||grant.host()?retroPad(nes):0;}
    public void inputRetroPad(int mask){if(role==Role.LOCAL){localInput(0,mask);return;}if(jni!=null){jni.input(mask);return;}if(!gunMode)input=grant.player()||grant.host()?mask&65535:0;}
    public void cabinetInput(int port,int mask){if(role==Role.LOCAL){localInput(port,mask);return;}if(!cabinetAuthority)return;checkAuthorityPort(port);if(grant.host()&&!closed&&(jni==null||jni.active()))cabinetInputs.input(port,mask,System.nanoTime());}
    public void cabinetRelease(int port){if(role==Role.LOCAL){localRelease(port);return;}if(!cabinetAuthority)return;checkAuthorityPort(port);if(grant.host())cabinetInputs.release(port);}
    public void cabinetCoin(int port,long sequence){if(role==Role.LOCAL)throw new IllegalStateException("LOCAL has no network coin authority");if(!cabinetAuthority)return;checkAuthorityPort(port);if(grant.host()&&!closed&&(jni==null||jni.active()))cabinetInputs.coin(port,sequence);}
    private void checkAuthorityPort(int port){if(port<0||port>=profile.ports())throw new IllegalArgumentException("Port not supported by core profile");}
    /** Only the computing host accepts canonical server input; gun peers are native spectators. */
    public void authoritativeGun(long revision,long sequence,int buttons,int aim){
        if(role==Role.LOCAL)throw new IllegalStateException("LOCAL light gun is not adapted");
        if(jni!=null){jni.authoritativeGun(revision,sequence,buttons,aim);return;}
        if(!closed&&!closing&&!localCloseRequested.get()&&grant.host()&&gunMode)gunInputs.offer(revision,sequence,buttons,aim,System.nanoTime());
    }
    public static int retroPad(int nes){return (nes&1)<<8 | (nes&2)>>>1 | nes&252;}
    public void receive(NetplayChunk chunk,boolean mayPlay) {
        receive(chunk,mayPlay?1:-1);
    }
    public void receive(NetplayChunk chunk,int authorizedPort) {
        if(role==Role.LOCAL)return; // Even a forged/late network message cannot create LOCAL pipes.
        if(jni!=null){jni.receive(chunk,authorizedPort);return;}
        if(authorizedPort< -1||authorizedPort>=profile.ports())return;
        if(closed||chunk.session()!=grant.session())return;
        if(chunk.kind()==NetplayChunk.ACK&&!grant.host()&&chunk.ticket().equals(grant.ticket())){connected.countDown();return;}
        if(chunk.kind()==NetplayChunk.OPEN&&grant.host()) {
            if(pipes.size()>=8||pipes.containsKey(chunk.ticket()))return;
            Pipe pipe=new Pipe(chunk.ticket());
            if(authorizedPort==0)return;
            if(pipes.putIfAbsent(chunk.ticket(),pipe)==null)daemon("PIQ-Netplay-host-pipe",()->openHost(pipe,gunMode||cabinetAuthority?-1:authorizedPort));
            return;
        }
        Pipe pipe=pipes.get(chunk.ticket());if(pipe==null)return;
        if(chunk.kind()==NetplayChunk.CLOSE){pipe.close(false);return;}
        if(chunk.kind()==NetplayChunk.DATA&&!pipe.offer(chunk)){log("Pipe queue or sequence rejected: buffered="+pipe.incoming.size()+" seq="+chunk.sequence());pipe.close(true,"Netplay 接收积压或数据顺序异常，请重新加入");}
    }
    private void openHost(Pipe pipe,int port) {
        Socket socket=null;
        try {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(!closed&&!pipe.done&&!ready&&System.nanoTime()<end)Thread.sleep(10);
            if(!ready||closed||pipe.done)throw new IOException("主持端尚未就绪");
            socket=sockets.own(new Socket());socket.connect(new InetSocketAddress(LOOP,nativePort),2000);socket.setTcpNoDelay(true);
            socket.getOutputStream().write(token.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(port<0?0:1<<port);socket.getOutputStream().flush();
            if(pipe.start(socket))send(new NetplayChunk(grant.session(),pipe.ticket,NetplayChunk.ACK,0,new byte[0]));
        }catch(Exception failure){sockets.release(socket);log("Peer startup failed: "+failure.getClass().getSimpleName());pipe.close(true);}
    }
    private void run() {
        try {
            checkOpen();
            if(localCloseRequested.get())return;
            String unavailable=unavailableReason();if(unavailable!=null)throw new IOException(role==Role.LOCAL?"RetroArch 本机运行仅支持 Windows x64 客户端":unavailable);
            phase("准备游戏与核心");
            LibretroContentFiles files=localFiles==null?verifiedFiles:Objects.requireNonNull(localFiles.call(),"LOCAL content files");
            byte[] rom=files==null?content.call():null;
            Map<String,byte[]> extras=files==null?auxiliary.call():Map.of();
            Map<String,String> auxiliaryHashes=new TreeMap<>();
            String romHash;
            long contentBytes;
            if(files!=null){
                validateFileContent(profile,files);
                romHash=files.main().sha256();auxiliaryHashes.putAll(files.auxiliaryHashes());contentBytes=files.size();
            }else{
                if(rom==null||rom.length<16||rom.length>profile.maxRomBytes())throw new IOException("ROM 大小异常");
                if(extras==null||extras.size()>4)throw new IOException("辅助文件数量异常");
                contentBytes=rom.length;
                var names=new HashSet<String>();names.add(profile.contentName().toLowerCase(Locale.ROOT));
                var copied=new TreeMap<String,byte[]>();
                for(var entry:extras.entrySet()){
                    String name=entry.getKey();byte[] bytes=entry.getValue();
                    if(role==Role.LOCAL)checkLocalContentName(name);
                    if((role!=Role.LOCAL&&!NetplayProfile.safeName(name))||!names.add(name.toLowerCase(Locale.ROOT))
                            ||bytes==null||bytes.length<1||bytes.length>(role==Role.LOCAL?LibretroContentFiles.MAX_AUXILIARY:16*1024*1024))
                        throw new IOException("辅助文件边界异常");
                    contentBytes+=bytes.length;
                    if(role==Role.LOCAL&&contentBytes>LibretroContentFiles.MAX_TOTAL)throw new IOException("LOCAL content bundle exceeds 128 MiB");
                    copied.put(name,role==Role.LOCAL?bytes.clone():bytes);
                }
                if(role==Role.LOCAL){
                    checkLocalContentName(profile.contentName());
                    if(contentBytes>LibretroContentFiles.MAX_TOTAL)throw new IOException("LOCAL content bundle exceeds 128 MiB");
                    rom=rom.clone();
                }
                extras=copied;romHash=NetplaySaveState.hash(rom);
                extras.forEach((name,bytes)->auxiliaryHashes.put(name,NetplaySaveState.hash(bytes)));
            }
            if(closed)return;
            Properties props=new Properties();try(var in=resource("/core/netplay/runtime.properties")){props.load(in);}
            String runnerHash=props.getProperty("exe.sha256");
            if(!RETROARCH_SHA.equalsIgnoreCase(runnerHash))throw new IOException("RetroArch 运行器身份不匹配，请使用配套主包");
            long required=contentBytes+160L*1024*1024;
            if(role==Role.LOCAL){
                required+=localConfig.core().maxBytes();
                for(var dependency:localConfig.dependencies())required+=dependency.bytes();
                saveIdentity=NetplaySaveState.localIdentity(localConfig,romHash,auxiliaryHashes,runnerHash);
            }else saveIdentity=NetplaySaveState.identity(profile,romHash,auxiliaryHashes);
            if(persistence!=null){
                phase(role==Role.LOCAL?"读取本局存档":"读取服务器存档");initialSave=persistence.load(saveIdentity);
                if(initialSave!=null)NetplaySaveState.decode(initialSave,saveIdentity);
                saveStatus=initialSave==null?"新进度，等待首次保存":"已校验存档，等待恢复";
                if(!persistence.enabled()){persistence=null;saveStatus="本局设置为不保存";}
            }
            if(closed)return;
            workspace=cn.piq.retro.storage.RuntimeWorkspace.create("netplay",required);directory=workspace.directory();
            extract("/core/netplay/piq-retroarch.exe",runnerHash,"retroarch.exe",32*1024*1024);
            extract(profile.owner(),profile.resource(),profile.sha(),"core.dll",role==Role.LOCAL?localConfig.core().maxBytes():128L*1024*1024);
            if(role==Role.LOCAL)for(var dependency:localConfig.dependencies()){
                extract(dependency.owner(),dependency.artifact().resource(),dependency.artifact().sha256(),dependency.fileName(),dependency.bytes());
                if(Files.size(directory.resolve(dependency.fileName()))!=dependency.bytes())throw new IOException("Fixed runtime dependency size mismatch");
            }
            if(files!=null)files.stage(directory,this::checkOpen);
            else{
                Files.write(directory.resolve(profile.contentName()),rom,StandardOpenOption.CREATE_NEW);
                for(var entry:extras.entrySet())Files.write(directory.resolve(entry.getKey()),entry.getValue(),StandardOpenOption.CREATE_NEW);
            }
            checkOpen();
            Files.writeString(directory.resolve("retroarch.cfg"),(role==Role.LOCAL?localConfiguration():cabinetConfig(grant,gunMode,cabinetAuthority,profile.ports())).replace("audio_out_rate = \"44100\"","audio_out_rate = \""+profile.sampleRate()+"\""),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("options.cfg"),profile.config(),StandardOpenOption.CREATE_NEW);
            if(initialSave!=null)Files.write(directory.resolve("checkpoint.seed"),NetplaySaveState.nativeSeed(NetplaySaveState.decode(initialSave,saveIdentity)),StandardOpenOption.CREATE_NEW);
            ipcListener=sockets.own(new ServerSocket(0,4,LOOP));ipcListener.setSoTimeout(500);
            if(role!=Role.LOCAL&&grant.host()){try(var reserve=new ServerSocket(0,1,LOOP)){nativePort=reserve.getLocalPort();}}
            else if(role!=Role.LOCAL) {
                phase("连接主持玩家");
                proxyListener=sockets.own(new ServerSocket(0,1,LOOP));proxyListener.setSoTimeout(1000);nativePort=proxyListener.getLocalPort();
                Pipe pipe=new Pipe(grant.ticket());pipes.put(pipe.ticket,pipe);
                send(new NetplayChunk(grant.session(),pipe.ticket,NetplayChunk.OPEN,0,new byte[0]));
                if(!connected.await(30,TimeUnit.SECONDS)||closed)throw new IOException("等待主持端 Netplay 连接超时");
                daemon("PIQ-Netplay-local-proxy",()->{
                    try {
                        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                        while(!closed&&System.nanoTime()<end)try{Socket accepted=sockets.own(proxyListener.accept());accepted.setTcpNoDelay(true);pipe.start(accepted);return;}catch(SocketTimeoutException ignored){}
                        pipe.close(true,"Netplay 核心连接超时，请重新加入");
                    }catch(IOException failure){pipe.close(true);}
                });
            }
            if(closed)return;
            phase("启动核心");
            var args=command(directory.resolve("retroarch.exe").toString());
            var builder=new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().put("PIQ_IPC_PORT",Integer.toString(ipcListener.getLocalPort()));builder.environment().put("PIQ_IPC_TOKEN",token);
            builder.environment().put("TZ","UTC0");
            builder.environment().put("PIQ_CABINET_AUTHORITY",cabinetAuthority&&ownsPersistence()?"1":"0");
            builder.environment().put("PIQ_SAVE_HOST",ownsPersistence()?"1":"0");
            builder.environment().remove("PIQ_SAVE_SEED");
            if(initialSave!=null)builder.environment().put("PIQ_SAVE_SEED","checkpoint.seed");
            builder.environment().put("TEMP",directory.toString());builder.environment().put("TMP",directory.toString());
            if(!replayValid()||closed||localCloseRequested.get())return;
            // Process creation and workspace marker IO never hold the public session lock.
            // If close wins inside the OS, the owner still retires this late child in cleanup().
            child=workspace.start(builder);CHILDREN.add(child);
            if(!replayValid()||closed||localCloseRequested.get())return;
            Process owned=child;
            daemon("PIQ-Netplay-log",()->processLog.read(owned.getInputStream()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(!closed&&ipc==null&&System.nanoTime()<deadline) {
                checkOpen();
                if(!owned.isAlive())throw new IOException(runtimeLabel()+" 原生端启动退出："+owned.exitValue());
                try {
                    Socket candidate=sockets.own(ipcListener.accept());candidate.setTcpNoDelay(true);candidate.setSoTimeout(1000);
                    try {if(Arrays.equals(candidate.getInputStream().readNBytes(32),token.getBytes(StandardCharsets.US_ASCII)))ipc=candidate;}
                    finally {if(ipc!=candidate)sockets.release(candidate);}
                }catch(SocketTimeoutException ignored){}
            }
            if(ipc==null)throw new IOException(runtimeLabel()+" 音画桥启动超时");
            phase("等待游戏画面");
            ipc.setSoTimeout(15000);
            var in=new DataInputStream(new BufferedInputStream(ipc.getInputStream()));var out=new DataOutputStream(ipc.getOutputStream());
            nextSaveNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!closed) {
                if(!replayValid()){discardSave=true;break;}
                int message=in.readInt();
                if(message==0x504e5356){readCheckpoint(in);continue;}
                if(message==0x504e5049){writeInputs(out);continue;}
                if(message!=0x504e5037)throw new IOException(runtimeLabel()+" 帧协议不匹配");
                long number=Integer.toUnsignedLong(in.readInt());int pixels=in.readInt(),audio=in.readInt(),rate=in.readInt();
                int width=in.readInt(),height=in.readInt(),aspect=in.readInt();
                int rotation=in.readInt();if(rotation<0||rotation>3)throw new IOException(runtimeLabel()+" 旋转边界异常");
                if(width<1||height<1||width>1024||height>1024||pixels!=0&&pixels!=width*height*4||audio<0||audio>32768||audio%4!=0||rate!=profile.sampleRate()||aspect<10000||aspect>800000)throw new IOException(runtimeLabel()+" 音画边界异常");
                byte[] bgra=new byte[pixels];in.readFully(bgra);byte[] pcm=new byte[audio];in.readFully(pcm);
                for(int i=0;i<bgra.length;i+=4){byte b=bgra[i];bgra[i]=bgra[i+2];bgra[i+2]=b;bgra[i+3]=(byte)255;}
                short[] stereo=new short[audio/2];float[] mono=new float[audio/4];for(int i=0;i<mono.length;i++){
                    int at=i*4;short l=(short)((pcm[at]&255)|(pcm[at+1]<<8)),r=(short)((pcm[at+2]&255)|(pcm[at+3]<<8));mono[i]=(l+r)/65536f;
                    stereo[i*2]=l;stereo[i*2+1]=r;
                }
                // PNP7 keeps libretro's CCW rotation; its bridge already supplies unrotated DAR.
                Frame frame=new Frame(number,bgra,mono,width,height,aspect/100000f,stereo,rate,legacyClockwiseRotation(rotation));
                synchronized(this){if(closed)break;while(!frames.offer(frame))frames.poll();framesReceived++;ready=true;phase=role==Role.LOCAL?"音画已就绪":"本机音画已就绪（不代表所有玩家已同步）";}
                if(persistence!=null&&!closing&&System.nanoTime()>=nextSaveNanos){nextSaveNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);saveNow();}
            }
        }catch(Exception|LinkageError failure){
            // EOF/exit and stdout delivery race. Wait only on the background owner, before
            // publishing error(), so adapters can capture the final rejection diagnostic.
            if(child!=null&&!closed&&!localCloseRequested.get())processLog.awaitDrain(500);
            String reason=failureReason(failure);
            if(workspace!=null)reason=workspace.failureMessage(reason);
            if(!closed&&!localCloseRequested.get()){
                fail(reason);log("Failure during "+phase+": "+failure.getClass().getSimpleName()+" / "+reason);
            }
        }
        finally{try{terminate();}finally{cleanup();}}
    }
    String runtimeLabel(){return role==Role.LOCAL?"RetroArch":"Netplay";}
    String failureReason(Throwable failure){
        if(failure instanceof SocketTimeoutException)return role==Role.LOCAL?"RetroArch 音画输出超时，请查看诊断后重试":"Netplay 音画中断或同步等待超时，请重新加入";
        if(failure instanceof EOFException)return runtimeLabel()+" 核心输出已结束，请查看诊断";
        return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
    }
    private void checkOpen()throws IOException{if(!replayValid()||closed||localCloseRequested.get()||Thread.currentThread().isInterrupted())throw new IOException("运行会话已取消");}
    static void validateFileContent(NetplayProfile profile,LibretroContentFiles files)throws IOException{
        if(!files.mainName().equals(profile.contentName())||files.main().size()<16
                ||files.main().size()>profile.maxRomBytes()||files.size()>LibretroContentFiles.MAX_TOTAL)
            throw new IOException("游戏文件身份或大小不符合本局声明");
        for(String name:files.files().keySet())checkLocalContentName(name);
    }
    private static void checkLocalContentName(String name)throws IOException{
        if(!NetplaySaveState.safeLocalName(name)||Set.of("retroarch.exe","core.dll","retroarch.cfg","options.cfg","checkpoint.seed","libunwind.dll").contains(name.toLowerCase(Locale.ROOT)))
            throw new IOException("Unsafe or reserved LOCAL content name");
    }
    /** Pure launch contract, package-visible for behavior tests. Content stays ASCII and relative. */
    List<String> command(String executable){
        var args=new ArrayList<>(List.of(executable,"-v","-c","retroarch.cfg","-L","core.dll","--sram-mode=noload-nosave"));
        if(role!=Role.LOCAL)args.addAll(List.of("--port="+nativePort,"--nick=PIQ","--check-frames=60"));
        for(int port=0;port<profile.ports();port++){args.add("-d");args.add((port+1)+":"+profile.deviceForPort(port));}
        if(role!=Role.LOCAL)args.add(grant.host()?"--host":"--connect=127.0.0.1");
        // Full-path cores such as MAME use dirname to construct their driver arguments.
        // Keep the path relative/ASCII, but never pass a bare filename in LOCAL mode.
        args.add(role==Role.LOCAL?"./"+profile.contentName():profile.contentName());return List.copyOf(args);
    }
    /** Sample once immediately before Netplay records the next frame, not on AV delivery. */
    void writeInputs(DataOutputStream out)throws IOException {
        boolean stopping=closing||closed||localCloseRequested.get();
        var aim=!stopping&&gunMode&&grant.host()?gunInputs.next(System.nanoTime()):NetplayGunMailbox.NEUTRAL;
        int[] pads;
        if(role==Role.LOCAL&&localInputProvider!=null&&!stopping){
            pads=Objects.requireNonNull(localInputProvider.get(),"LOCAL input provider returned null").clone();
            if(pads.length!=profile.ports())throw new IOException("LOCAL input port count");
            for(int mask:pads)if((mask&~4095)!=0)throw new IOException("LOCAL input exceeds 12-bit mask");
            pads=Arrays.copyOf(pads,4);
        }else pads=stopping?new int[4]:cabinetInputs.next(System.nanoTime());
        if(stopping)Arrays.fill(pads,0);
        out.writeInt(stopping?0:gunMode?retroPad(aim.buttons()):input);
        out.writeInt(aim.coordinates());out.writeInt(aim.flags());
        for(int mask:pads)out.writeInt(mask);
        synchronized(this){out.writeInt(saveRequest!=saveSent?saveRequest:0);saveSent=saveRequest;}
        out.flush();
    }
    private void readCheckpoint(DataInputStream in)throws IOException{
        int request=in.readInt();long frame=in.readLong();int ns=in.readInt(),nr=in.readInt(),nc=in.readInt();
        CompletableFuture<byte[]> pending;
        synchronized(this){pending=capture;if(!ownsPersistence()||request!=saveSent||pending==null||pending.isDone())throw new IOException("非预期的 "+runtimeLabel()+" 存档回复");}
        if(ns==0&&nr==0&&nc==0){pending.completeExceptionally(new IOException("核心无法生成保存状态"));return;}
        if(frame<0||ns<1||ns>NetplaySaveState.MAX_STATE||nr<0||nr>NetplaySaveState.MAX_RAM||nc<0||nc>NetplaySaveState.MAX_RTC)throw new IOException(runtimeLabel()+" 存档边界异常");
        byte[] state=new byte[ns],ram=new byte[nr],rtc=new byte[nc];in.readFully(state);in.readFully(ram);in.readFully(rtc);
        pending.complete(NetplaySaveState.encode(new NetplaySaveState.Parts(saveIdentity,frame,state,ram,rtc)));
    }
    public static String config(boolean host,boolean player) {
        return """
                video_driver = "null"
                audio_driver = "null"
                input_driver = "null"
                input_joypad_driver = "null"
                audio_enable = "true"
                audio_out_rate = "44100"
                audio_sync = "false"
                audio_rate_control = "false"
                video_vsync = "true"
                video_refresh_rate = "60.0988138974405"
                fastforward_ratio = "1.0"
                pause_nonactive = "false"
                pause_on_disconnect = "false"
                config_save_on_exit = "false"
                netplay_public_announce = "false"
                netplay_nat_traversal = "false"
                netplay_use_mitm_server = "false"
                netplay_allow_pause = "false"
                netplay_allow_slaves = "false"
                netplay_max_connections = "8"
                netplay_share_digital = "0"
                netplay_share_analog = "0"
                netplay_check_frames = "60"
                menu_show_start_screen = "false"
                menu_enable_widgets = "false"
                content_history_size = "0"
                history_list_enable = "false"
                content_runtime_log = "false"
                content_runtime_log_aggregate = "false"
                savestate_auto_save = "false"
                savestate_auto_load = "false"
                rewind_enable = "false"
                run_ahead_enabled = "false"
                log_verbosity = "true"
                libretro_log_level = "1"
                savefile_directory = "."
                savestate_directory = "."
                system_directory = "."
                log_dir = "."
                cache_directory = "."
                screenshot_directory = "."
                core_options_path = "options.cfg"
                """+"netplay_start_as_spectator = \""+(!host&&!player)+"\"\nnetplay_request_device_p"+(host?1:2)+" = \"true\"\n";
    }
    public static String config(boolean host,boolean player,boolean gun){
        return config(host,player&&!gun)+(gun&&host?"netplay_request_device_p2 = \"true\"\n":"");
    }
    public static String config(Grant grant,boolean gun){
        String value=config(grant.host(),grant.player(),gun);
        return !grant.host()&&grant.player()&&!gun?value.replace("netplay_request_device_p2 =", "netplay_request_device_p"+(grant.port()+1)+" ="):value;
    }
    public static String cabinetConfig(Grant grant,boolean gun,boolean authority,int ports){
        if(!authority)return config(grant,gun);
        String value=config(grant.host(),false,false);
        if(grant.host())for(int p=1;p<ports;p++)value+="netplay_request_device_p"+(p+1)+" = \"true\"\n";
        return value;
    }
    /** No network command interface, public room or spectator/seat configuration in LOCAL. */
    public static String localConfiguration(){
        var out=new StringBuilder();
        config(false,false).lines().filter(line->!line.startsWith("netplay_")).forEach(line->out.append(line).append('\n'));
        return out.append("network_cmd_enable = \"false\"\nstdin_cmd_enable = \"false\"\n").toString();
    }
    public String diagnostic(){if(jni!=null)return jni.diagnostic();return (role==Role.LOCAL?"RetroArch LOCAL 状态：":"RetroArch Netplay 状态：")+status()
            +"\n角色："+role+" · 协议："+RETROARCH_PROTOCOL+"\n运行器 SHA-256："+RETROARCH_SHA+"\n核心 SHA-256："+profile.sha()
            +(role==Role.LOCAL?"":"\n当前传输通道："+pipes.size()+"（仅传输计数，不代表同步验收）")
            +"\n存档："+saveStatus+"\n已接收画面："+framesReceived+"\n关闭方式："+(forcedTermination?"已请求强制结束子进程（不等于原生正常卸载）":"未请求强制结束")+"\n"+processLog.snapshot();}
    private void log(String text){processLog.append("\n[PIQ transport] "+text+'\n');}
    private InputStream resource(String path)throws IOException{var in=NetplayProcess.class.getResourceAsStream(path);if(in==null)throw new IOException("缺少内置 "+runtimeLabel()+" 组件："+path);return in;}
    private void extract(String resource,String sha,String name,long max)throws Exception {
        extract(NetplayProcess.class,resource,sha,name,max);
    }
    private void extract(Class<?> owner,String resource,String sha,String name,long max)throws Exception {
        if(sha==null||!sha.matches("[0-9a-fA-F]{64}"))throw new IOException("运行库校验清单异常");
        try(var in=owner.getResourceAsStream(resource);var out=Files.newOutputStream(directory.resolve(name),StandardOpenOption.CREATE_NEW)) {
            if(in==null)throw new IOException("缺少附属核心："+resource);
            var digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;long total=0;
            while((n=in.read(b))>=0){checkOpen();total+=n;if(total>max)throw new IOException("运行库大小异常");digest.update(b,0,n);out.write(b,0,n);}
            if(!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha))throw new IOException("运行库校验失败");
        }
    }
    private void send(NetplayChunk chunk){if(role!=Role.LOCAL&&!closed)sender.accept(chunk);}
    private final class Pipe {
        final UUID ticket;final NetplayInbox incoming=new NetplayInbox();
        volatile Socket socket;volatile boolean done;long rx,tx;
        Pipe(UUID ticket){this.ticket=ticket;}
        synchronized boolean offer(NetplayChunk c){return !done&&c.sequence()==rx++&&incoming.offer(c.bytes());}
        synchronized boolean start(Socket value)throws IOException {
            if(done||closed){sockets.release(value);return false;}socket=value;socket.setSoTimeout(0);
            daemon("PIQ-Netplay-pipe-write",()->{
                try{var out=value.getOutputStream();while(!closed&&!done){var b=incoming.poll(250);if(b!=null){out.write(b);out.flush();}}}catch(Exception ignored){}finally{close(true);}
            });
            daemon("PIQ-Netplay-pipe-read",()->{
                try{var in=value.getInputStream();var pacer=new NetplayPacer();byte[] b=new byte[NetplayChunk.LIMIT];int n;
                    while(!closed&&!done&&(n=in.read(b))>=0)if(n>0){
                        long until=System.nanoTime()+pacer.delay(n,System.nanoTime());
                        while(!closed&&!done&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(Math.min(5_000_000L,until-System.nanoTime()));
                        if(!closed&&!done)send(new NetplayChunk(grant.session(),ticket,NetplayChunk.DATA,tx++,Arrays.copyOf(b,n)));
                    }}
                catch(Exception ignored){}finally{close(true);}
            });
            return true;
        }
        void close(boolean notify){close(notify,"Netplay 连接已结束，请重新加入");}
        void close(boolean notify,String reason){
            synchronized(this){if(done)return;done=true;sockets.release(socket);incoming.close();pipes.remove(ticket,this);}
            // Never invoke the transport/caller while holding the per-peer monitor.
            if(!grant.host())fail(reason);
            if(notify)send(new NetplayChunk(grant.session(),ticket,NetplayChunk.CLOSE,0,new byte[0]));
            if(!grant.host()&&!closed)NetplayProcess.this.close();
        }
    }
    @Override public void close() {
        if(!cn.piq.retro.replay.ReplayGuard.valid(replayEpoch))discardSave=true;
        if(jni!=null&&discardSave){jni.closeWithoutSave();return;}
        if(jni!=null){jni.close();return;}
        if(!localCloseRequested.compareAndSet(false,true))return;
        closing=true;input=0;
        // Abort callbacks, transport and CompletableFuture continuations may do IO.
        // Every RetroArch role closes off the caller thread, not just LOCAL.
        daemon("PIQ-RetroArch-close",this::closeInternal);
    }
    public void closeWithoutSave(){discardSave=true;close();}
    private void closeInternal(){
        synchronized(this){if(closed)return;closing=true;input=0;gunInputs.close();cabinetInputs.close();}
        if(!replayValid()||persistence==null||!ready||error!=null){terminate();return;}
        // Gameplay routes may already have retired. Disconnect native peers so the final completed
        // frame cannot wait forever for input from a seat that the server has just revoked.
        for(Pipe pipe:List.copyOf(pipes.values()))pipe.close(false);
        // No waiting on the Minecraft/render thread. Keep only the bounded final save lane alive.
        daemon("PIQ-Netplay-final-save",()->{
            try{
                try{commit.get(30,TimeUnit.SECONDS);}catch(ExecutionException ignored){}
                saveNow().get(30,TimeUnit.SECONDS);
                if(!replayValid())return;
                persistence.finish().get(10,TimeUnit.SECONDS);
            }catch(Exception failure){saveStatus="最终保存未确认，保留最近已确认版本";fail(saveStatus);log("Final save not confirmed: "+failure.getClass().getSimpleName());}
            finally{terminate();}
        });
    }
    private void terminate() {
        synchronized(this){if(closed)return;closed=true;ready=false;input=0;gunInputs.close();cabinetInputs.close();}
        try{
            // Release native IO before any caller callback can block. The owner may
            // reclaim its process concurrently, but cannot confirm full termination yet.
            connected.countDown();sockets.close();
            for(Pipe pipe:List.copyOf(pipes.values()))pipe.close(false);
            frames.clear();
            if(capture!=null&&!capture.isDone())capture.completeExceptionally(new IOException(runtimeLabel()+" 已终止，未取得新存档"));
            if(persistence!=null)persistence.abort();
            if(role!=Role.LOCAL)try{sender.accept(new NetplayChunk(grant.session(),grant.ticket(),NetplayChunk.CLOSE,0,new byte[0]));}catch(RuntimeException failure){log("Close notification failed: "+failure.getClass().getSimpleName());}
            transportTerminated.complete(null);
            if(!started)terminated.complete(null);
        }catch(Exception failure){
            transportTerminated.completeExceptionally(failure);cleanupFailed(failure);
        }
    }
    private void cleanup() {
        Process owned=child;
        try{
            if(owned!=null){
                if(!owned.waitFor(1500,TimeUnit.MILLISECONDS)){
                    forcedTermination=true;owned.destroyForcibly();
                }
                if(!owned.waitFor(5,TimeUnit.SECONDS)){
                    owned.onExit().thenRun(()->{CHILDREN.remove(owned);if(workspace!=null)workspace.close();});
                    throw new IOException("子进程退出尚未确认，运行目录保留；不能启动替代会话");
                }
                processLog.awaitDrain(500);
                log("Child exit="+owned.exitValue()+"; forced="+forcedTermination);
                CHILDREN.remove(owned);
            }
            if(workspace!=null){
                workspace.close();
                // RuntimeWorkspace retries transient Windows file locks internally. Its close()
                // intentionally does not throw; only observed removal proves cleanup succeeded.
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!Files.notExists(directory,LinkOption.NOFOLLOW_LINKS)&&System.nanoTime()<end)Thread.sleep(25);
                if(!Files.notExists(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("子进程已退出，但运行目录清理未确认；保留待安全回收");
            }
            // A different close thread may still own abort/transport callbacks.
            // Unknown residual work is a close failure, never a successful retirement.
            transportTerminated.get(5,TimeUnit.SECONDS);
            terminated.complete(null);
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();cleanupFailed(interrupted);
        }catch(Exception failure){cleanupFailed(failure);}
        finally{if(workspace!=null)workspace.close();}
    }
    private void cleanupFailed(Exception failure){
        synchronized(this){if(error==null)error="关闭未确认："+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}
        log("Cleanup not confirmed: "+failure.getClass().getSimpleName());terminated.completeExceptionally(failure);
    }
    private static InetAddress loopback(){try{return InetAddress.getByAddress(new byte[]{127,0,0,1});}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    private static void daemon(String name,Runnable work){var t=new Thread(work,name);t.setDaemon(true);t.start();}
}
