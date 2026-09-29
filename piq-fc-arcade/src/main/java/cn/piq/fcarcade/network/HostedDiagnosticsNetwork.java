package cn.piq.fcarcade.network;

import cn.piq.fcarcade.cabinet.CabinetHostingConfig;
import cn.piq.fcarcade.server.hosted.HostedVideoPacer;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A global server setting, not device authority; read is public and mutation requires a current OP2. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class HostedDiagnosticsNetwork {
    private static final Map<ServerPlayer,Long> LAST = Collections.synchronizedMap(new WeakHashMap<>());
    public interface ClientSink { boolean accepts(Connection connection); void receive(State state); }
    private static volatile ClientSink sink;
    private HostedDiagnosticsNetwork() {}
    public static void clientSink(ClientSink value) { sink=Objects.requireNonNull(value); }
    public static void request(UUID nonce,int expected,int desired) {
        PacketDistributor.sendToServer(new Request(nonce,expected,desired));
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        TrafficPayloadRegistrar.create(event,"hosted-diagnostics-1")
            .playToServer(Request.TYPE,Request.CODEC,(p,c)->{
                var source=c.connection();c.enqueueWork(()->{
                    if(c.player() instanceof ServerPlayer player && source!=null && source.isConnected()
                            && player.connection.getConnection()==source)handle(player,p);
                });
            })
            .playToClient(State.TYPE,State.CODEC,(p,c)->{
                var source=c.connection();c.enqueueWork(()->{
                    var current=sink;if(source!=null&&source.isConnected()&&current!=null&&current.accepts(source))current.receive(p);
                });
            });
    }
    private static void handle(ServerPlayer player,Request request) {
        var server=player.getServer();
        if(server==null||!server.isSameThread()||player.hasDisconnected()||!player.connection.getConnection().isConnected()
                ||server.getPlayerList().getPlayer(player.getUUID())!=player)return;
        long now=System.nanoTime();
        Long last=LAST.get(player);if(last!=null&&now-last>=0&&now-last<200_000_000L)return;
        LAST.put(player,now); // Limit reads as well as writes, before any disk access.
        int current=CabinetHostingConfig.videoFps();boolean editable=player.hasPermissions(2);
        String result="此设置影响本服务器全部托管设备；不改变游戏速度或音频。";
        if(request.desired()!=-1) {
            if(!editable)result="仅管理员（OP2）可以修改服务器托管帧率。";
            else if(request.expected()!=current)result="设置已变化，已刷新当前值；请核对后重新选择。";
            else if(request.desired()!=current&&!CabinetHostingConfig.saveVideoFps(request.desired()))
                result="配置未保存；保留原帧率，请检查服务器日志。";
            else result="已保存：视频投递上限 "+request.desired()+" FPS。带宽、TPS 和核心速度可能降低实际帧率。";
        }
        PacketDistributor.sendToPlayer(player,new State(request.nonce(),CabinetHostingConfig.videoFps(),editable,
                CabinetHostingConfig.enabled(),result));
    }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","hosted_diagnostics_"+name); }
    public record Request(UUID nonce,int expected,int desired) implements CustomPacketPayload {
        public Request { Objects.requireNonNull(nonce);if(expected!=0&&!HostedVideoPacer.supported(expected)||desired!=-1&&!HostedVideoPacer.supported(desired))throw new IllegalArgumentException("Video settings request"); }
        public static final Type<Request> TYPE=new Type<>(id("request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.nonce);b.writeVarInt(p.expected);b.writeVarInt(p.desired);},b->new Request(b.readUUID(),b.readVarInt(),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record State(UUID nonce,int fps,boolean editable,boolean enabled,String reason) implements CustomPacketPayload {
        public State { Objects.requireNonNull(nonce);if(!HostedVideoPacer.supported(fps)||reason==null||reason.length()>256)throw new IllegalArgumentException("Video settings state"); }
        public static final Type<State> TYPE=new Type<>(id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.nonce);b.writeVarInt(p.fps);b.writeBoolean(p.editable);b.writeBoolean(p.enabled);b.writeUtf(p.reason,256);},b->new State(b.readUUID(),b.readVarInt(),b.readBoolean(),b.readBoolean(),b.readUtf(256)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
