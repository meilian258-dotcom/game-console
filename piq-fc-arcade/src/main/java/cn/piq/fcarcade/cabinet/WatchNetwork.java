package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Read-only observation protocol: no target chosen by clients, no controller or ROM authority. */
public final class WatchNetwork {
    private WatchNetwork() {}
    public interface ClientSink {
        boolean acceptsConnection(Connection connection);
        void start(Start value);
        default void netplay(NetplayStart value) {}
        void stop(Stop value);
        void heartbeat(Heartbeat value);
        void hostDemand(HostDemand value);
        void stream(Stream value);
    }
    private static volatile ClientSink clientSink;
    public static void setClientSink(ClientSink sink){clientSink=Objects.requireNonNull(sink);}
    public static void send(CustomPacketPayload payload){
        PacketDistributor.sendToServer(payload);
        if(payload instanceof Heartbeat h)cn.piq.fcarcade.network.ModTrafficProbe.upload(heartbeatBytes(h.revision()));
    }
    static int heartbeatBytes(long revision){int bytes=17;while((revision>>>=7)!=0)bytes++;return bytes;}
    public static void register(RegisterPayloadHandlersEvent event){
        CabinetRooms.registerWatchProvider();
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"watch-2")
            .playToServer(Available.TYPE,Available.CODEC,(p,c)->server(c,s->WatchService.available(s,p)))
            .playToServer(Release.TYPE,Release.CODEC,(p,c)->server(c,s->WatchService.release(s,p)))
            .playToServer(Media.TYPE,Media.CODEC,(p,c)->server(c,s->WatchService.media(s,p)))
            .playBidirectional(Heartbeat.TYPE,Heartbeat.CODEC,(p,c)->{
                if(c.player() instanceof ServerPlayer)server(c,s->{
                    if(!s.connection.getConnection().isMemoryConnection())cn.piq.fcarcade.network.ServerTrafficMeter.heartbeat(false,heartbeatBytes(p.revision()));
                    WatchService.heartbeat(s,p);
                });else {
                    cn.piq.fcarcade.network.ModTrafficProbe.download(heartbeatBytes(p.revision()));dispatch(c,s->s.heartbeat(p));
                }
            })
            .playToClient(Start.TYPE,Start.CODEC,(p,c)->dispatch(c,s->s.start(p)))
            .playToClient(NetplayStart.TYPE,NetplayStart.CODEC,(p,c)->dispatch(c,s->s.netplay(p)))
            .playToClient(Stop.TYPE,Stop.CODEC,(p,c)->dispatch(c,s->s.stop(p)))
            .playToClient(HostDemand.TYPE,HostDemand.CODEC,(p,c)->dispatch(c,s->s.hostDemand(p)))
            .playToClient(Stream.TYPE,Stream.CODEC,(p,c)->dispatch(c,s->s.stream(p)));
    }
    private static void dispatch(IPayloadContext context,Consumer<ClientSink> action){
        Connection source=context.connection();
        context.enqueueWork(()->{var sink=clientSink;if(source!=null&&sink!=null&&sink.acceptsConnection(source))action.accept(sink);});
    }
    private static void server(IPayloadContext context,Consumer<ServerPlayer> action){
        Connection source=context.connection();
        context.enqueueWork(()->{if(source!=null&&context.player() instanceof ServerPlayer player
                &&player.connection.getConnection()==source&&WatchService.current(player))action.accept(player);});
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","watch_"+name);}
    private static void revision(long value){if(value<=0)throw new IllegalArgumentException("watch revision");}
    private static void anchor(RegistryFriendlyByteBuf b,WatchAnchor a){b.writeBlockPos(a.pos());b.writeUUID(a.identity());}
    private static WatchAnchor anchor(RegistryFriendlyByteBuf b){return new WatchAnchor(b.readBlockPos(),b.readUUID());}
    private static void descriptor(RegistryFriendlyByteBuf b,WatchDescriptor d){
        b.writeUtf(d.provider().toString(),128);b.writeUUID(d.source());b.writeUUID(d.hostLease());b.writeUtf(d.dimension().toString(),128);
        anchor(b,d.origin());b.writeBoolean(d.link()!=null);if(d.link()!=null)b.writeUUID(d.link());b.writeVarInt(d.screens().size());for(var a:d.screens())anchor(b,a);
    }
    private static WatchDescriptor descriptor(RegistryFriendlyByteBuf b){
        var provider=ResourceLocation.parse(b.readUtf(128));var source=b.readUUID();var host=b.readUUID();var dimension=ResourceLocation.parse(b.readUtf(128));
        var origin=anchor(b);var link=b.readBoolean()?b.readUUID():null;int count=b.readVarInt();if(count<1||count>2)throw new IllegalArgumentException("watch screens");
        var screens=new ArrayList<WatchAnchor>(count);for(int i=0;i<count;i++)screens.add(anchor(b));return new WatchDescriptor(provider,source,host,dimension,origin,link,screens);
    }
    public record Start(long revision,UUID lease,WatchDescriptor descriptor) implements CustomPacketPayload {
        public Start{WatchNetwork.revision(revision);Objects.requireNonNull(lease);Objects.requireNonNull(descriptor);}
        public static final Type<Start> TYPE=new Type<>(id("start"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Start> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);WatchNetwork.descriptor(b,p.descriptor);},b->new Start(b.readVarLong(),b.readUUID(),WatchNetwork.descriptor(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record NetplayStart(Start watch,long wire,UUID ticket,ResourceLocation backend,String romHash) implements CustomPacketPayload {
        public NetplayStart{Objects.requireNonNull(watch);Objects.requireNonNull(ticket);Objects.requireNonNull(backend);
            if(wire<0||backend.toString().length()>128||!CabinetGameManifest.hash(romHash))throw new IllegalArgumentException("Netplay observer grant");}
        public static final Type<NetplayStart> TYPE=new Type<>(id("netplay"));
        public static final StreamCodec<RegistryFriendlyByteBuf,NetplayStart> CODEC=StreamCodec.of((b,p)->{
            Start.CODEC.encode(b,p.watch);b.writeVarLong(p.wire);b.writeUUID(p.ticket);b.writeUtf(p.backend.toString(),128);b.writeUtf(p.romHash,64);
        },b->new NetplayStart(Start.CODEC.decode(b),b.readVarLong(),b.readUUID(),ResourceLocation.parse(b.readUtf(128)),b.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Stop(long revision,UUID lease,String reason) implements CustomPacketPayload {
        public Stop{WatchNetwork.revision(revision);Objects.requireNonNull(lease);if(reason==null||reason.length()>128)throw new IllegalArgumentException("watch reason");}
        public static final Type<Stop> TYPE=new Type<>(id("stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Stop> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);b.writeUtf(p.reason,128);},b->new Stop(b.readVarLong(),b.readUUID(),b.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Heartbeat(long revision,UUID lease) implements CustomPacketPayload {
        public Heartbeat{WatchNetwork.revision(revision);Objects.requireNonNull(lease);}
        public static final Type<Heartbeat> TYPE=new Type<>(id("heartbeat"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Heartbeat> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);},b->new Heartbeat(b.readVarLong(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Release(long revision,UUID lease) implements CustomPacketPayload {
        public Release{WatchNetwork.revision(revision);Objects.requireNonNull(lease);}
        public static final Type<Release> TYPE=new Type<>(id("release"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Release> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);},b->new Release(b.readVarLong(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Available(boolean enabled) implements CustomPacketPayload {
        public static final Type<Available> TYPE=new Type<>(id("available"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Available> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.enabled),b->new Available(b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record HostDemand(long revision,WatchDescriptor descriptor,int watchers) implements CustomPacketPayload {
        public HostDemand{WatchNetwork.revision(revision);Objects.requireNonNull(descriptor);if(watchers<0||watchers>WatchLedger.MAX_VIEWERS)throw new IllegalArgumentException("watchers");}
        public boolean needed(){return watchers>0;}
        public static final Type<HostDemand> TYPE=new Type<>(id("demand"));
        public static final StreamCodec<RegistryFriendlyByteBuf,HostDemand> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);WatchNetwork.descriptor(b,p.descriptor);b.writeVarInt(p.watchers);},b->new HostDemand(b.readVarLong(),WatchNetwork.descriptor(b),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Media(CabinetRoomNetwork.Media media) implements CustomPacketPayload {
        public Media{Objects.requireNonNull(media);}
        public static final Type<Media> TYPE=new Type<>(id("media"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Media> CODEC=StreamCodec.of((b,p)->CabinetRoomNetwork.Media.CODEC.encode(b,p.media),b->new Media(CabinetRoomNetwork.Media.CODEC.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Stream(UUID lease,CabinetRoomNetwork.Media media) implements CustomPacketPayload {
        public Stream{Objects.requireNonNull(lease);Objects.requireNonNull(media);}
        public static final Type<Stream> TYPE=new Type<>(id("stream"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Stream> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);CabinetRoomNetwork.Media.CODEC.encode(b,p.media);},b->new Stream(b.readUUID(),CabinetRoomNetwork.Media.CODEC.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
