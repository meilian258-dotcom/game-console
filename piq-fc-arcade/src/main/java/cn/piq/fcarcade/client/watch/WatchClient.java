package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.ClientArcadeEvents;
import cn.piq.fcarcade.client.cabinet.WatchAudio;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.retro.input.InputOwnership;
import cn.piq.retro.libretro.jni.NativeLibretroBridge;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplayNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.concurrent.*;

/** Nearby read-only displays. Each source owns its lease, preparation and native lifetime. */
@EventBusSubscriber(modid="piq_fc_arcade", value=Dist.CLIENT)
public final class WatchClient implements WatchNetwork.ClientSink {
    public interface DisplayAdapter {
        boolean valid(WatchDescriptor descriptor);
        boolean isParticipant(WatchDescriptor descriptor);
        default boolean blocksNetplay(WatchDescriptor descriptor){return isParticipant(descriptor);}
        default int maximumDistance(){return 68;}
        default boolean visible(RenderLevelStageEvent event,WatchDescriptor descriptor){return true;}
        void render(RenderLevelStageEvent event, WatchDescriptor descriptor, ResourceLocation texture, float aspect, int rotation);
        /** Distance attenuation only; Minecraft volume is applied once by the shared receiver. */
        float volume(WatchDescriptor descriptor);
    }
    private record Source(ResourceLocation provider,UUID source,UUID hostLease) {
        static Source of(WatchDescriptor d){return new Source(d.provider(),d.source(),d.hostLease());}
    }
    private static final Map<ResourceLocation,DisplayAdapter> DISPLAYS = new HashMap<>();
    private static final Map<ResourceLocation,Consumer<WatchNetwork.HostDemand>> HOSTS = new HashMap<>();
    private static final Map<String,IntSupplier> PENDING_CONTROLS = new HashMap<>();
    private static final Map<Source,Demand> DEMANDS = new LinkedHashMap<>();
    private static final Map<UUID,Receiver> RECEIVERS = new LinkedHashMap<>();
    // Deliberately retained across disconnect: unbind/cancel is not proof that a native slot is free.
    private static final List<Receiver> CLOSING = new ArrayList<>();
    private static final WatchLeaseState HISTORY = new WatchLeaseState();
    private static final long TIMEOUT = 6_000_000_000L;
    private static Connection connection;
    private static int ticks;
    private static WatchNetwork.Available available;
    private static boolean shuttingDown;
    private static final ThreadPoolExecutor CONTENT=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"PIQ-Watch-Content");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private record Demand(WatchNetwork.HostDemand value,long expires,boolean expired) {}
    private static final class Receiver {
        final WatchNetwork.Start start;
        final WatchNetwork.NetplayStart grant;
        final Connection connection;
        final Source source;
        long expiresAt=System.nanoTime()+TIMEOUT;
        WatchMediaStream media;
        WatchAudio audio;
        NetplayProcess netplay;
        NetplayWatchContent.Preparation preparation;
        WatchPreparationTask<CabinetBackend.NetplayContent> task;
        NetplayProcess.Frame nativeVideo;
        DynamicTexture texture;
        ResourceLocation textureId;
        float aspect;
        int rotation;
        Boolean nativeMode;
        boolean closed,audible;
        final WatchMonoResampler mono=new WatchMonoResampler();
        Receiver(WatchNetwork.Start start,WatchNetwork.NetplayStart grant,Connection connection){
            this.start=start;this.grant=grant;this.connection=connection;source=Source.of(start.descriptor());
        }
        boolean released(){return (task==null||task.done())&&(netplay==null||netplay.terminated().isDone());}
        boolean nativeClaim(){return grant!=null&&!Boolean.FALSE.equals(nativeMode);}
    }
    private WatchClient() {}

    public static synchronized void registerDisplay(ResourceLocation provider, DisplayAdapter adapter) {
        Objects.requireNonNull(provider); Objects.requireNonNull(adapter);
        if (DISPLAYS.size() >= 8 || DISPLAYS.putIfAbsent(provider, adapter) != null) throw new IllegalArgumentException("Duplicate watch display");
    }
    public static synchronized void registerHost(ResourceLocation provider, Consumer<WatchNetwork.HostDemand> host) {
        Objects.requireNonNull(provider); Objects.requireNonNull(host);
        if (HOSTS.size() >= 8 || HOSTS.putIfAbsent(provider, host) != null) throw new IllegalArgumentException("Duplicate watch host");
    }
    /** Read-only declarations for actual controller preparation before its native reservation exists. */
    public static synchronized void registerPendingControl(String id,IntSupplier query){
        if(PENDING_CONTROLS.size()>=8||PENDING_CONTROLS.putIfAbsent(Objects.requireNonNull(id),Objects.requireNonNull(query))!=null)
            throw new IllegalArgumentException("Duplicate pending controller provider");
    }
    private static int pendingControls(){
        return WatchPresentationPolicy.pendingControls(PENDING_CONTROLS.values());
    }
    @EventBusSubscriber(modid="piq_fc_arcade", bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> { WatchNetwork.setClientSink(new WatchClient()); CabinetWatchDisplay.register();
                NetplayWatchContent.register(CabinetRooms.WATCH_PROVIDER,cn.piq.fcarcade.client.cabinet.CabinetClientBackends::prepareObservation);
                registerPendingControl("fc",ClientArcadeEvents::pendingNativeControlClaims);
                registerPendingControl("cabinet",cn.piq.fcarcade.client.cabinet.CabinetClientBackends::pendingNativeControlClaims);
                cn.piq.fcarcade.client.NetworkDiagnosticsClient.registerDevices("watch",WatchClient::diagnosticDevices); });
        }
    }
    private static Connection liveConnection() {
        var c=Minecraft.getInstance().getConnection();
        return c==null||!c.getConnection().isConnected()?null:c.getConnection();
    }
    private static boolean availableNow() {
        var mc=Minecraft.getInstance();
        return !shuttingDown&&mc.level!=null&&mc.player!=null&&mc.player.isAlive();
    }
    private static boolean operating(){return InputOwnership.occupied()||ClientArcadeEvents.isControlling();}
    private static boolean live(Receiver r){return !r.closed&&RECEIVERS.get(r.start.lease())==r&&r.connection==connection;}
    private static void syncConnection() {
        Connection next=liveConnection();
        if(next==connection)return;
        closeAll(false);clearDemands();connection=next;HISTORY.connection(next);available=null;ticks=0;
    }
    @Override public boolean acceptsConnection(Connection source){return source!=null&&source==liveConnection();}
    private static Receiver admit(WatchNetwork.Start start,WatchNetwork.NetplayStart grant){
        syncConnection();
        if(HISTORY.matches(start.revision(),start.lease()))return null;
        Source source=Source.of(start.descriptor());
        if(!HISTORY.begin(source,start.revision(),start.lease()))return null;
        for(var old:List.copyOf(RECEIVERS.values()))if(old.source.equals(source))close(old,false);
        var r=new Receiver(start,grant,connection);
        // MEDIA and historical process Netplay keep their single-source policy; never silently downgrade JNI.
        boolean incompatible=grant==null?!RECEIVERS.isEmpty():RECEIVERS.values().stream().anyMatch(v->v.grant==null||Boolean.FALSE.equals(v.nativeMode));
        if(incompatible||grant!=null&&RECEIVERS.size()>=capacity()){
            HISTORY.retire(start.lease());release(r);return null;
        }
        RECEIVERS.put(start.lease(),r);
        if(!valid(r)){close(r,true);return null;}
        return r;
    }
    @Override public void start(WatchNetwork.Start value){
        var r=admit(value,null);if(r==null)return;
        try{r.media=new WatchMediaStream(value.descriptor().source(),value.descriptor().hostLease(),false);r.audio=new WatchAudio();}
        catch(RuntimeException|LinkageError failure){close(r,true);}
    }
    @Override public void netplay(WatchNetwork.NetplayStart grant){if(admit(grant.watch(),grant)!=null)prepareNext();}
    /** Capture/download one source at a time; cancellation retains the claim until the worker really returns. */
    private static void prepareNext(){
        if(RECEIVERS.values().stream().anyMatch(r->r.task!=null&&!r.task.done())
                ||CLOSING.stream().anyMatch(r->r.task!=null&&!r.task.done()))return;
        for(var r:List.copyOf(RECEIVERS.values())){
            if(r.grant==null||r.task!=null||!valid(r))continue;
            try{
                var captured=NetplayWatchContent.prepare(r.grant,r.connection);r.preparation=captured;
                r.task=new WatchPreparationTask<>(captured.load());
                r.task.result().whenComplete((content,failure)->Minecraft.getInstance().execute(()->{
                    if(!live(r))return;
                    if(!valid(r)){close(r,true);return;}
                    if(failure!=null){nativeFailure(r,failure);return;}
                    try{
                        int rate=content.profile().sampleRate();
                        if(rate!=48000&&!(rate==44100&&captured.factory()!=null))throw new IllegalArgumentException("Unsupported observer audio rate");
                        r.nativeMode=content.profile().jni()!=null;
                        if(!r.nativeMode&&(RECEIVERS.size()!=1||operating()))throw new IllegalStateException("Process Netplay supports one idle observer only");
                        var grant=r.grant;
                        r.netplay=captured.open(new NetplayProcess.Grant(grant.wire(),grant.ticket(),false,false),content,
                                chunk->NetplayNetwork.upstream(r.connection,chunk));
                        NetplayNetwork.bind(r.connection,r.netplay);r.netplay.start();r.audio=new WatchAudio();
                    }catch(Exception|LinkageError bad){nativeFailure(r,bad);}
                }));
                CONTENT.execute(r.task);
            }catch(Exception|LinkageError bad){nativeFailure(r,bad);}
            return;
        }
    }
    private static void nativeFailure(Receiver r,Throwable failure){
        cn.piq.fcarcade.client.ui.DeviceNotices.record("Netplay 旁观",r.netplay==null?"旁观准备失败":r.netplay.diagnostic(),failure);
        org.slf4j.LoggerFactory.getLogger("PIQ Watch").warn("Netplay observer failed for {}",r.source,failure);
        close(r,true);
    }
    /** Compatibility for legacy/private callers that do not yet know their source. */
    public static void controlStarting(){closeAll(true);}
    /** Relinquishes only the exact wire; unrelated screens remain alive. Close is asynchronous. */
    public static void controlStarting(long wire){for(var r:List.copyOf(RECEIVERS.values()))if(r.grant!=null&&r.grant.wire()==wire)close(r,true);}
    public static void controlStarting(WatchDescriptor d){if(d!=null)for(var r:List.copyOf(RECEIVERS.values()))if(r.source.equals(Source.of(d)))close(r,true);}
    public static boolean netplayWatching(ResourceLocation provider){return RECEIVERS.values().stream().anyMatch(r->r.grant!=null&&r.source.provider().equals(provider));}
    public static boolean hasNetplayWatch(){return RECEIVERS.values().stream().anyMatch(r->r.grant!=null);}
    public static boolean hasNetplayWatch(long wire){return RECEIVERS.values().stream().anyMatch(r->r.grant!=null&&r.grant.wire()==wire);}
    public static boolean hasNetplayWatch(WatchDescriptor d){return d!=null&&RECEIVERS.values().stream().anyMatch(r->r.grant!=null&&r.source.equals(Source.of(d)));}
    @Override public void stop(WatchNetwork.Stop value){
        syncConnection();var r=RECEIVERS.get(value.lease());
        if(HISTORY.stop(value.revision(),value.lease())&&r!=null&&r.start.revision()==value.revision())close(r,false);
    }
    @Override public void heartbeat(WatchNetwork.Heartbeat value){
        syncConnection();var r=RECEIVERS.get(value.lease());
        if(r!=null&&HISTORY.matches(value.revision(),value.lease()))r.expiresAt=System.nanoTime()+TIMEOUT;
    }
    @Override public void hostDemand(WatchNetwork.HostDemand value){
        syncConnection();var source=Source.of(value.descriptor());
        if(shuttingDown||!HOSTS.containsKey(source.provider()))return;
        Demand old=DEMANDS.get(source);
        if(old!=null&&(value.revision()<old.value().revision()||value.revision()==old.value().revision()&&!value.equals(old.value())))return;
        if(old==null&&DEMANDS.size()>=128){
            DEMANDS.entrySet().removeIf(e->e.getValue().expired());
            if(DEMANDS.size()>=128)return;
        }
        DEMANDS.put(source,new Demand(value,System.nanoTime()+TIMEOUT,false));dispatchDemand(value);
    }
    private static void dispatchDemand(WatchNetwork.HostDemand value){
        var consumer=HOSTS.get(value.descriptor().provider());
        if(consumer!=null)try{consumer.accept(value);}catch(RuntimeException|LinkageError ignored){/* No observer failure exits a game. */}
    }
    private static void clearDemands(){
        for(Demand old:List.copyOf(DEMANDS.values()))dispatchDemand(new WatchNetwork.HostDemand(old.value().revision(),old.value().descriptor(),0));
        DEMANDS.clear();
    }
    @Override public void stream(WatchNetwork.Stream value){
        syncConnection();var r=RECEIVERS.get(value.lease());
        if(r==null||r.media==null||!valid(r))return;
        var p=value.media();var d=r.start.descriptor();
        if(!d.source().equals(p.room())||!d.hostLease().equals(p.hostMember()))return;
        r.media.accept(new CabinetMediaPacket(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data()));
    }
    private static boolean valid(Receiver r){
        if(!live(r)||connection!=liveConnection()||!availableNow()||System.nanoTime()>r.expiresAt)return false;
        if((r.grant==null||Boolean.FALSE.equals(r.nativeMode))&&operating())return false;
        var d=r.start.descriptor();var mc=Minecraft.getInstance();
        if(!d.dimension().equals(mc.level.dimension().location()))return false;
        DisplayAdapter adapter=DISPLAYS.get(d.provider());
        if(adapter==null||distanceSquared(d)>(double)adapter.maximumDistance()*adapter.maximumDistance())return false;
        try{return adapter.valid(d)&&!(r.grant!=null?adapter.blocksNetplay(d):adapter.isParticipant(d));}
        catch(RuntimeException|LinkageError invalid){return false;}
    }
    private static List<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device> diagnosticDevices(){
        // WatchDescriptor carries no host synchronization mode; do not infer it from an idle setting.
        var result=new ArrayList<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device>();
        for(var r:RECEIVERS.values()){
            if(!valid(r)||r.grant==null&&(r.media==null||r.media.error()!=null))continue;
            var d=r.start.descriptor();
            result.add(new cn.piq.fcarcade.client.NetworkDiagnosticsView.Device("旁观设备 @ "+d.origin().pos().toShortString(),
                    r.grant!=null?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.WATCH_NETPLAY:cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.WATCH_MEDIA,
                    cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.WATCHING,distanceSquared(d)));
        }
        return List.copyOf(result);
    }
    public static double distanceSquared(WatchDescriptor descriptor){
        var player=Minecraft.getInstance().player;if(player==null)return Double.POSITIVE_INFINITY;
        double result=Double.POSITIVE_INFINITY;
        for(WatchAnchor screen:descriptor.screens()){var p=screen.pos();result=Math.min(result,player.distanceToSqr(p.getX()+.5,p.getY()+.5,p.getZ()+.5));}
        return result;
    }
    private static void release(Receiver r){
        if(r.connection!=null&&r.connection==liveConnection()&&r.connection.isConnected())
            try{WatchNetwork.send(new WatchNetwork.Release(r.start.revision(),r.start.lease()));}catch(RuntimeException|LinkageError ignored){}
    }
    private static void closeAll(boolean notify){for(var r:List.copyOf(RECEIVERS.values()))close(r,notify);}
    private static void close(Receiver r,boolean notify){
        if(r.closed)return;
        r.closed=true;RECEIVERS.remove(r.start.lease(),r);HISTORY.retire(r.start.lease());
        if(r.preparation!=null)try{r.preparation.cancel().run();}catch(RuntimeException|LinkageError ignored){}
        if(r.task!=null){r.task.cancel();CONTENT.remove(r.task);}
        if(r.netplay!=null){
            NetplayNetwork.unbind(r.connection,r.netplay);
            try{r.netplay.close();}catch(RuntimeException|LinkageError ignored){}
        }
        if(r.media!=null)try{r.media.close();}catch(RuntimeException|LinkageError ignored){}
        if(r.audio!=null)try{r.audio.close();}catch(RuntimeException|LinkageError ignored){}
        try{dropTexture(r);}catch(RuntimeException|LinkageError ignored){}
        r.nativeVideo=null;
        if(!r.released())CLOSING.add(r);
        if(notify)release(r);
    }
    private static void dropTexture(Receiver r){
        if(r.textureId!=null)Minecraft.getInstance().getTextureManager().release(r.textureId);
        else if(r.texture!=null)r.texture.close();
        r.texture=null;r.textureId=null;
    }
    private static int capacity(){
        CLOSING.removeIf(Receiver::released);
        if(RECEIVERS.values().stream().anyMatch(r->Boolean.FALSE.equals(r.nativeMode)))return 1;
        // Sample free first so our own in-flight reservation is not transiently subtracted twice.
        // This remains a budget hint, not an atomic reservation: the native four-slot gate is final.
        int free=NativeLibretroBridge.freeSlotsIfLoaded();
        int held=0,closingUnreserved=0;
        for(var r:RECEIVERS.values())if(r.nativeClaim()&&r.netplay!=null&&r.netplay.nativeSlotHeld())held++;
        for(var r:CLOSING)if(r.nativeClaim()&&(r.netplay==null||!r.netplay.nativeSlotHeld()))closingUnreserved++;
        return WatchPresentationPolicy.capacity(free,held,closingUnreserved,pendingControls());
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        syncConnection();CLOSING.removeIf(Receiver::released);
        if(shuttingDown||connection==null||!connection.isConnected())return;
        ticks++;
        boolean enabled=availableNow();var next=new WatchNetwork.Available(enabled,enabled?capacity():0);
        if(!next.equals(available)||ticks%40==0){available=next;WatchNetwork.send(next);}
        long now=System.nanoTime();
        for(var entry:List.copyOf(DEMANDS.entrySet())){
            Demand old=entry.getValue();
            if(!old.expired()&&now>old.expires()){
                DEMANDS.put(entry.getKey(),new Demand(old.value(),old.expires(),true));
                dispatchDemand(new WatchNetwork.HostDemand(old.value().revision(),old.value().descriptor(),0));
            }
        }
        for(var r:List.copyOf(RECEIVERS.values())){
            if(!valid(r)||r.grant==null&&(r.media==null||r.media.error()!=null)){close(r,true);continue;}
            if(r.netplay!=null&&r.netplay.error()!=null){nativeFailure(r,new IllegalStateException(r.netplay.error()));continue;}
            if(ticks%40==0)WatchNetwork.send(new WatchNetwork.Heartbeat(r.start.revision(),r.start.lease()));
        }
        prepareNext();selectAudio();
        for(var r:List.copyOf(RECEIVERS.values()))try{drain(r);}catch(RuntimeException|LinkageError failure){close(r,true);}
    }
    private static void selectAudio(){
        var candidates=new ArrayList<WatchPresentationPolicy.Audible<Receiver>>();
        for(var r:RECEIVERS.values())candidates.add(new WatchPresentationPolicy.Audible<>(r,distanceSquared(r.start.descriptor()),
                valid(r)&&r.audio!=null&&(r.grant==null||r.netplay!=null&&r.netplay.ready())));
        var primary=WatchPresentationPolicy.primary(candidates,operating());
        for(var r:RECEIVERS.values())if(r.audible!=(r==primary)){
            r.audible=r==primary;r.mono.reset();if(r.audio!=null)r.audio.reset();
        }
    }
    private static void drain(Receiver r){
        if(r.audio==null)return;
        var mc=Minecraft.getInstance();var adapter=DISPLAYS.get(r.start.descriptor().provider());
        float distanceGain=adapter.volume(r.start.descriptor());
        float gain=!r.audible||!Float.isFinite(distanceGain)||mc.isPaused()?0:Math.max(0,Math.min(1,distanceGain));
        r.audio.gain(.6F*gain*mc.options.getSoundSourceVolume(SoundSource.MASTER)*mc.options.getSoundSourceVolume(SoundSource.BLOCKS));
        if(r.netplay!=null)for(int i=0;i<6;i++){
            var frame=r.netplay.poll();if(frame==null)break;
            if(frame.rgba().length>0)r.nativeVideo=frame;
            if(r.audible){
                if(frame.stereo().length>0&&frame.sampleRate()==48000)r.audio.offer(frame.stereo());
                else if(frame.mono().length>0&&frame.sampleRate()==44100)r.audio.offer(r.mono.convert(frame.mono()));
            }
        }else if(r.media!=null)for(int i=0;i<6;i++){short[] pcm=r.media.pollAudio();if(pcm==null)break;if(r.audible)r.audio.offer(pcm);}
    }
    private static void texture(Receiver r,int width,int height){
        if(r.texture!=null&&r.texture.getPixels()!=null&&r.texture.getPixels().getWidth()==width&&r.texture.getPixels().getHeight()==height)return;
        dropTexture(r);r.texture=new DynamicTexture(width,height,false);r.texture.setFilter(false,false);
        r.textureId=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","nearby_watch_screen/"+r.start.lease());
        Minecraft.getInstance().getTextureManager().register(r.textureId,r.texture);
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event){
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;
        selectAudio();
        for(var r:List.copyOf(RECEIVERS.values())){
            if(!valid(r)){close(r,true);continue;}
            try{
                drain(r);var adapter=DISPLAYS.get(r.start.descriptor().provider());var d=r.start.descriptor();
                if(!adapter.visible(event,d)){r.nativeVideo=null;if(r.media!=null)r.media.pollVideo();continue;}
                if(r.grant!=null){
                    var frame=r.nativeVideo;r.nativeVideo=null;
                    if(frame!=null){
                        texture(r,frame.width(),frame.height());var pixels=r.texture.getPixels();var rgba=frame.rgba();
                        for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++){
                            int i=(y*frame.width()+x)*4;pixels.setPixelRGBA(x,y,0xff000000|(rgba[i]&255)|((rgba[i+1]&255)<<8)|((rgba[i+2]&255)<<16));
                        }
                        r.texture.upload();r.aspect=frame.aspect();r.rotation=frame.rotation();
                    }
                }else if(r.media!=null){
                    var frame=r.media.pollVideo();
                    if(frame!=null){
                        texture(r,frame.width(),frame.height());var pixels=r.texture.getPixels();
                        for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++)pixels.setPixelRGBA(x,y,frame.abgr()[y*frame.width()+x]|0xff000000);
                        r.texture.upload();r.aspect=frame.displayAspect();r.rotation=frame.rotation();
                    }
                }
                if(r.textureId!=null)adapter.render(event,d,r.textureId,r.aspect,r.rotation);
            }catch(RuntimeException|LinkageError failure){close(r,true);}
        }
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event){shuttingDown=true;closeAll(false);clearDemands();}
}
