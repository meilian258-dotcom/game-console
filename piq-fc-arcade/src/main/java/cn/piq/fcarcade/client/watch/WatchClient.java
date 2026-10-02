package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.ClientArcadeEvents;
import cn.piq.fcarcade.client.cabinet.WatchAudio;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.retro.input.InputOwnership;
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
import java.util.concurrent.*;
import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.fcarcade.netplay.NetplayNetwork;

/** Nearby read-only display: media reception or authorized Netplay emulation, never a seat/input owner. */
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
    private static final Map<ResourceLocation,DisplayAdapter> DISPLAYS = new HashMap<>();
    private static final Map<ResourceLocation,Consumer<WatchNetwork.HostDemand>> HOSTS = new HashMap<>();
    private static final Map<ResourceLocation,Demand> DEMANDS = new HashMap<>();
    private static final WatchLeaseState HISTORY = new WatchLeaseState();
    private static final long TIMEOUT = 6_000_000_000L;
    private static Connection connection;
    private static WatchNetwork.Start current;
    private static WatchMediaStream media;
    private static WatchAudio audio;
    private static NetplayProcess netplay;
    private static WatchNetwork.NetplayStart netplayGrant;
    private static NetplayWatchContent.Preparation preparation;
    private static Future<?> preparing;
    private static final ExecutorService CONTENT=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"PIQ-Watch-Content");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static NetplayProcess.Frame nativeVideo;
    private static DynamicTexture texture;
    private static ResourceLocation textureId;
    private static long expiresAt;
    private static int ticks, rotation;
    private static float aspect;
    private static Boolean available;
    private static boolean shuttingDown;
    private record Demand(WatchNetwork.HostDemand value, long expires, boolean expired) {}
    private WatchClient() {}

    public static synchronized void registerDisplay(ResourceLocation provider, DisplayAdapter adapter) {
        Objects.requireNonNull(provider); Objects.requireNonNull(adapter);
        if (DISPLAYS.size() >= 8 || DISPLAYS.putIfAbsent(provider, adapter) != null) throw new IllegalArgumentException("Duplicate watch display");
    }
    public static synchronized void registerHost(ResourceLocation provider, Consumer<WatchNetwork.HostDemand> host) {
        Objects.requireNonNull(provider); Objects.requireNonNull(host);
        if (HOSTS.size() >= 8 || HOSTS.putIfAbsent(provider, host) != null) throw new IllegalArgumentException("Duplicate watch host");
    }
    @EventBusSubscriber(modid="piq_fc_arcade", bus=EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> { WatchNetwork.setClientSink(new WatchClient()); CabinetWatchDisplay.register();
                NetplayWatchContent.register(CabinetRooms.WATCH_PROVIDER,cn.piq.fcarcade.client.cabinet.CabinetClientBackends::prepareObservation);
                cn.piq.fcarcade.client.NetworkDiagnosticsClient.registerDevices("watch",WatchClient::diagnosticDevices); });
        }
    }
    private static Connection liveConnection() {
        var c = Minecraft.getInstance().getConnection();
        return c == null || !c.getConnection().isConnected() ? null : c.getConnection();
    }
    private static boolean availableNow() {
        var mc = Minecraft.getInstance();
        return !shuttingDown && mc.level != null && mc.player != null && mc.player.isAlive()
                && !InputOwnership.occupied() && !ClientArcadeEvents.isControlling();
    }
    private static void syncConnection() {
        Connection next = liveConnection();
        if (next == connection) return;
        close(false); clearDemands(); connection = next; HISTORY.connection(next); available = null; ticks = 0;
    }
    @Override public boolean acceptsConnection(Connection source) { return source != null && source == liveConnection(); }
    @Override public void start(WatchNetwork.Start value) {
        syncConnection();
        if (current != null && HISTORY.matches(value.revision(), value.lease())) return;
        if (!HISTORY.begin(value.revision(), value.lease())) return;
        closeResources(); current = value; expiresAt = System.nanoTime() + TIMEOUT;
        if (!valid()) { close(true); return; }
        try { media = new WatchMediaStream(value.descriptor().source(), value.descriptor().hostLease(), false); audio = new WatchAudio(); }
        catch (RuntimeException | LinkageError failure) { close(true); }
    }
    @Override public void netplay(WatchNetwork.NetplayStart grant){
        syncConnection();var value=grant.watch();
        if(current!=null&&HISTORY.matches(value.revision(),value.lease()))return;
        if(!HISTORY.begin(value.revision(),value.lease()))return;
        closeResources();current=value;netplayGrant=grant;expiresAt=System.nanoTime()+TIMEOUT;
        if(!valid()){close(true);return;}
        var exact=connection;
        try{
            var captured=NetplayWatchContent.prepare(grant,exact);preparation=captured;
            preparing=CONTENT.submit(()->{try{
                var content=captured.load().call();
                if(Thread.currentThread().isInterrupted())return;
                Minecraft.getInstance().execute(()->{
                    if(current!=value||netplayGrant!=grant||connection!=exact||!valid())return;
                    try{
                        if(content.profile().sampleRate()!=48000)throw new IllegalArgumentException("Observer audio requires 48 kHz");
                        netplay=new NetplayProcess(new NetplayProcess.Grant(grant.wire(),grant.ticket(),false,false),content::rom,
                                chunk->NetplayNetwork.upstream(exact,chunk),content.profile(),content::auxiliary,captured.cabinetTopology());
                        NetplayNetwork.bind(exact,netplay);netplay.start();audio=new WatchAudio();
                    }catch(Exception|LinkageError bad){nativeFailure(bad);}
                });
            }catch(Exception|LinkageError bad){Minecraft.getInstance().execute(()->{if(current==value&&netplayGrant==grant&&connection==exact)nativeFailure(bad);});}});
        }catch(Exception|LinkageError bad){nativeFailure(bad);}
    }
    private static void nativeFailure(Throwable failure){
        cn.piq.fcarcade.client.ui.DeviceNotices.record("Netplay 旁观",netplay==null?"旁观准备失败":netplay.diagnostic(),failure);
        org.slf4j.LoggerFactory.getLogger("PIQ Watch").warn("Netplay observer failed",failure);close(true);
    }
    /** Immediate relinquishment before a new controller can bind the same native wire. */
    public static void controlStarting(){close(true);}
    public static boolean netplayWatching(ResourceLocation provider){return netplayGrant!=null&&current!=null&&current.descriptor().provider().equals(provider);}
    public static boolean hasNetplayWatch(){return netplayGrant!=null&&current!=null;}
    @Override public void stop(WatchNetwork.Stop value) {
        syncConnection();
        if (HISTORY.stop(value.revision(), value.lease())) { current = null; closeResources(); }
    }
    @Override public void heartbeat(WatchNetwork.Heartbeat value) {
        syncConnection();
        if (current != null && HISTORY.matches(value.revision(), value.lease())) expiresAt = System.nanoTime() + TIMEOUT;
    }
    @Override public void hostDemand(WatchNetwork.HostDemand value) {
        syncConnection();
        ResourceLocation provider = value.descriptor().provider();
        if (shuttingDown || !HOSTS.containsKey(provider)) return;
        Demand old = DEMANDS.get(provider);
        if (old != null && (value.revision() < old.value().revision()
                || value.revision() == old.value().revision() && !value.equals(old.value()))) return;
        DEMANDS.put(provider, new Demand(value, System.nanoTime() + TIMEOUT, false));
        dispatchDemand(value);
    }
    private static void dispatchDemand(WatchNetwork.HostDemand value) {
        var consumer = HOSTS.get(value.descriptor().provider());
        if (consumer != null) try { consumer.accept(value); } catch (RuntimeException | LinkageError ignored) { /* Observer failure never exits a game. */ }
    }
    private static void clearDemands() {
        for (Demand old : List.copyOf(DEMANDS.values())) dispatchDemand(new WatchNetwork.HostDemand(old.value().revision(), old.value().descriptor(), 0));
        DEMANDS.clear();
    }
    @Override public void stream(WatchNetwork.Stream value) {
        syncConnection();
        if (current == null || media == null || !current.lease().equals(value.lease()) || !valid()) return;
        var p = value.media(); var d = current.descriptor();
        if (!d.source().equals(p.room()) || !d.hostLease().equals(p.hostMember())) return;
        media.accept(new CabinetMediaPacket(p.room(), p.hostMember(), p.sequence(), p.kind(), p.index(), p.count(), p.width(), p.height(), p.aspect(), p.rotation(), p.rawLength(), p.data()));
    }
    private static boolean valid() {
        if (current == null || connection != liveConnection() || !availableNow() || System.nanoTime() > expiresAt) return false;
        var d = current.descriptor(); var mc = Minecraft.getInstance();
        if (!d.dimension().equals(mc.level.dimension().location())) return false;
        DisplayAdapter adapter = DISPLAYS.get(d.provider());
        // Actual per-machine entry/exit range is enforced by the server lease.
        if (adapter == null || distanceSquared(d) > (double)adapter.maximumDistance()*adapter.maximumDistance()) return false;
        try { return adapter.valid(d) && !(netplayGrant!=null?adapter.blocksNetplay(d):adapter.isParticipant(d)); }
        catch (RuntimeException | LinkageError invalid) { return false; }
    }
    private static List<cn.piq.fcarcade.client.NetworkDiagnosticsView.Device> diagnosticDevices() {
        if(!valid()||(netplayGrant==null&&(media==null||media.error()!=null)))return List.of();
        var d=current.descriptor();var origin=d.origin().pos();
        // WatchDescriptor intentionally carries no host synchronization mode. Do not infer it from an idle setting.
        return List.of(new cn.piq.fcarcade.client.NetworkDiagnosticsView.Device("旁观设备 @ "+origin.toShortString(),
                netplayGrant!=null?cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.WATCH_NETPLAY:cn.piq.fcarcade.client.NetworkDiagnosticsView.Mode.WATCH_MEDIA,
                cn.piq.fcarcade.client.NetworkDiagnosticsView.Role.WATCHING,distanceSquared(d)));
    }
    public static double distanceSquared(WatchDescriptor descriptor) {
        var player = Minecraft.getInstance().player;
        if (player == null) return Double.POSITIVE_INFINITY;
        double result = Double.POSITIVE_INFINITY;
        for (WatchAnchor screen : descriptor.screens()) { var p = screen.pos(); result = Math.min(result, player.distanceToSqr(p.getX()+.5, p.getY()+.5, p.getZ()+.5)); }
        return result;
    }
    private static void close(boolean notify) {
        var old = current; current = null; HISTORY.retire(); closeResources();
        if (notify && old != null && connection != null && connection == liveConnection() && connection.isConnected())
            WatchNetwork.send(new WatchNetwork.Release(old.revision(), old.lease()));
    }
    private static void closeResources() {
        netplayGrant=null;nativeVideo=null;
        if(preparation!=null){preparation.cancel().run();preparation=null;}
        if(preparing!=null){preparing.cancel(true);preparing=null;}
        if(netplay!=null){NetplayNetwork.unbind(connection,netplay);netplay.close();netplay=null;}
        if (media != null) { media.close(); media = null; }
        if (audio != null) { audio.close(); audio = null; }
        dropTexture();
    }
    private static void dropTexture() {
        if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
        else if (texture != null) texture.close();
        texture = null; textureId = null;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        syncConnection(); if (shuttingDown || connection == null || !connection.isConnected()) return;
        boolean enabled = availableNow();
        if (available == null || available != enabled || ++ticks % 40 == 0) {
            available = enabled; WatchNetwork.send(new WatchNetwork.Available(enabled));
        }
        long now = System.nanoTime();
        for (var entry : List.copyOf(DEMANDS.entrySet())) {
            Demand old = entry.getValue();
            if (!old.expired() && now > old.expires()) {
                DEMANDS.put(entry.getKey(), new Demand(old.value(), old.expires(), true));
                dispatchDemand(new WatchNetwork.HostDemand(old.value().revision(), old.value().descriptor(), 0));
            }
        }
        if (current == null) return;
        if (!valid() || netplayGrant==null&&(media == null || media.error() != null)) { close(true); return; }
        if(netplay!=null&&netplay.error()!=null){nativeFailure(new IllegalStateException(netplay.error()));return;}
        if (ticks % 40 == 0) WatchNetwork.send(new WatchNetwork.Heartbeat(current.revision(), current.lease()));
        try { drainAudio(); } catch (RuntimeException | LinkageError failure) { close(true); }
    }
    private static void drainAudio() {
        if (current == null || audio == null) return;
        var mc = Minecraft.getInstance(); DisplayAdapter adapter = DISPLAYS.get(current.descriptor().provider());
        float distanceGain = adapter.volume(current.descriptor());
        float gain = !Float.isFinite(distanceGain) || mc.isPaused() ? 0 : Math.max(0, Math.min(1, distanceGain));
        audio.gain(.6F * gain * mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.BLOCKS));
        if(netplay!=null){for(int i=0;i<6;i++){var frame=netplay.poll();if(frame==null)break;
            if(frame.rgba().length>0)nativeVideo=frame;if(frame.stereo().length>0)audio.offer(frame.stereo());}}
        else if(media!=null)for (int i = 0; i < 6; i++) { short[] pcm = media.pollAudio(); if (pcm == null) break; audio.offer(pcm); }
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || current == null) return;
        if (!valid()) { close(true); return; }
        try {
            if(!DISPLAYS.get(current.descriptor().provider()).visible(event,current.descriptor())){
                // Discard stale presentation frames, but keep the authorized session/audio draining.
                drainAudio();nativeVideo=null;if(media!=null)media.pollVideo();return;
            }
            if(netplayGrant!=null){
                drainAudio();var frame=nativeVideo;nativeVideo=null;if(frame==null){if(textureId!=null)DISPLAYS.get(current.descriptor().provider()).render(event,current.descriptor(),textureId,aspect,rotation);return;}
                if(texture==null||texture.getPixels()==null||texture.getPixels().getWidth()!=frame.width()||texture.getPixels().getHeight()!=frame.height()){
                    dropTexture();texture=new DynamicTexture(frame.width(),frame.height(),false);texture.setFilter(false,false);
                    textureId=ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","nearby_watch_screen");Minecraft.getInstance().getTextureManager().register(textureId,texture);
                }
                var pixels=texture.getPixels();var rgba=frame.rgba();for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++){
                    int i=(y*frame.width()+x)*4;pixels.setPixelRGBA(x,y,0xff000000|(rgba[i]&255)|((rgba[i+1]&255)<<8)|((rgba[i+2]&255)<<16));}
                texture.upload();aspect=frame.aspect();rotation=frame.rotation();DISPLAYS.get(current.descriptor().provider()).render(event,current.descriptor(),textureId,aspect,rotation);return;
            }
            if (media == null) return;
            var frame = media.pollVideo();
            if (frame != null) {
                if (texture == null || texture.getPixels() == null || texture.getPixels().getWidth() != frame.width() || texture.getPixels().getHeight() != frame.height()) {
                    dropTexture(); texture = new DynamicTexture(frame.width(), frame.height(), false); texture.setFilter(false, false);
                    textureId = ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "nearby_watch_screen");
                    Minecraft.getInstance().getTextureManager().register(textureId, texture);
                }
                var pixels = texture.getPixels();
                for (int y = 0; y < frame.height(); y++) for (int x = 0; x < frame.width(); x++) pixels.setPixelRGBA(x, y, frame.abgr()[y*frame.width()+x] | 0xff000000);
                texture.upload(); aspect = frame.displayAspect(); rotation = frame.rotation();
            }
            drainAudio();
            if (textureId != null) DISPLAYS.get(current.descriptor().provider()).render(event, current.descriptor(), textureId, aspect, rotation);
        } catch (RuntimeException | LinkageError failure) { close(true); }
    }
    @SubscribeEvent public static void shutdown(GameShuttingDownEvent event) { shuttingDown = true; close(false); clearDemands(); }
}
