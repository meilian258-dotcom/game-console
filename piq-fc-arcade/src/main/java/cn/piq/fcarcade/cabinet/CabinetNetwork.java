package cn.piq.fcarcade.cabinet;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Separate, bounded protocol; never accepts a client-selected world target for authority. */
public final class CabinetNetwork {
    private CabinetNetwork() {}
    public interface ClientSink {
        default boolean acceptsConnection(net.minecraft.network.Connection source) { return false; }
        void openMenu(Menu menu);
        void openBackend(Launch launch);
        void closed(Closed closed);
    }
    private static volatile ClientSink clientSink;
    public static void setClientSink(ClientSink sink) { clientSink = Objects.requireNonNull(sink); }

    public static void register(RegisterPayloadHandlersEvent event) {
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cabinet-4")
                .playToServer(PowerPress.TYPE,PowerPress.CODEC,(p,c)->{
                    var source=c.connection();c.enqueueWork(()->{
                        if(c.player() instanceof ServerPlayer player&&source!=null&&source.isConnected()&&player.connection.getConnection()==source)
                            ServerCabinets.pressPower(player);
                    });
                })
                .playToServer(Choose.TYPE, Choose.CODEC, (p,c) -> c.enqueueWork(() -> {
                    if (c.player() instanceof ServerPlayer player) ServerCabinets.choose(player, p);
                }))
                .playToServer(Heartbeat.TYPE, Heartbeat.CODEC, (p,c) -> c.enqueueWork(() -> {
                    if (c.player() instanceof ServerPlayer player) ServerCabinets.heartbeat(player, p.lease());
                }))
                .playToServer(Release.TYPE, Release.CODEC, (p,c) -> c.enqueueWork(() -> {
                    if (c.player() instanceof ServerPlayer player) ServerCabinets.release(player, p.lease());
                }))
                .playToClient(Menu.TYPE, Menu.CODEC, (p,c) -> dispatch(c,s->s.openMenu(p)))
                .playToClient(Launch.TYPE, Launch.CODEC, (p,c) -> dispatch(c,s->s.openBackend(p)))
                .playToClient(Closed.TYPE, Closed.CODEC, (p,c) -> dispatch(c,s->s.closed(p)));
        CabinetRoomNetwork.register(event);
        WatchNetwork.register(event);
        CabinetJoinNetwork.register(event);
    }
    private static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext context,java.util.function.Consumer<ClientSink> action){
        var source=context.connection();
        context.enqueueWork(()->{var sink=clientSink;if(source!=null&&sink!=null&&sink.acceptsConnection(source))action.accept(sink);});
    }
    private static ResourceLocation id(String value) { return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade",value); }
    private static void writeId(RegistryFriendlyByteBuf b,ResourceLocation id) { b.writeUtf(id.toString(),128); }
    private static ResourceLocation readId(RegistryFriendlyByteBuf b) { return ResourceLocation.parse(b.readUtf(128)); }
    static void writeTarget(RegistryFriendlyByteBuf b,CabinetTarget t) {
        writeId(b,t.dimension()); b.writeBlockPos(t.anchor()); b.writeUUID(t.identity()); b.writeBoolean(t.dual());
    }
    static CabinetTarget readTarget(RegistryFriendlyByteBuf b) {
        return new CabinetTarget(readId(b),b.readBlockPos(),b.readUUID(),b.readBoolean());
    }
    private static void checkedId(ResourceLocation id) {
        if(id==null||id.toString().length()>128)throw new IllegalArgumentException("Invalid backend id");
    }
    public static void send(CustomPacketPayload payload) { PacketDistributor.sendToServer(payload); }

    public record PowerPress() implements CustomPacketPayload {
        public static final Type<PowerPress> TYPE=new Type<>(id("cabinet_power_press"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PowerPress> CODEC=StreamCodec.of((b,p)->{},b->new PowerPress());
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }

    public record Menu(CabinetTarget target,UUID token,List<CabinetBackends.Entry> entries,ResourceLocation selected,boolean debugTool,String gameInfo) implements CustomPacketPayload {
        public Menu(CabinetTarget target,UUID token,List<CabinetBackends.Entry> entries,ResourceLocation selected,boolean debugTool){this(target,token,entries,selected,debugTool,"");}
        public Menu(CabinetTarget target,UUID token,List<CabinetBackends.Entry> entries,ResourceLocation selected){this(target,token,entries,selected,false);}
        public Menu {
            CabinetGameInfo.check(gameInfo);
            Objects.requireNonNull(target);Objects.requireNonNull(token);checkedId(selected);
            if(entries==null||entries.isEmpty()||entries.size()>CabinetBackends.MAX_BACKENDS)
                throw new IllegalArgumentException("Invalid backend list");
            entries=List.copyOf(entries);
            if(entries.stream().map(CabinetBackends.Entry::id).distinct().count()!=entries.size())
                throw new IllegalArgumentException("Duplicate backend list entries");
        }
        public static final Type<Menu> TYPE=new Type<>(id("cabinet_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Menu> CODEC=StreamCodec.of((b,p)->{
            writeTarget(b,p.target());b.writeUUID(p.token());b.writeVarInt(p.entries().size());
            for(var entry:p.entries()){writeId(b,entry.id());b.writeUtf(entry.displayName(),64);b.writeBoolean(entry.localOnly());}
            writeId(b,p.selected());b.writeBoolean(p.debugTool());b.writeUtf(p.gameInfo(),192);
        },b->{
            var target=readTarget(b);var token=b.readUUID();int size=b.readVarInt();
            if(size<1||size>CabinetBackends.MAX_BACKENDS)throw new IllegalArgumentException("Backend list too large");
            List<CabinetBackends.Entry> entries=new ArrayList<>(size);
            for(int i=0;i<size;i++)entries.add(new CabinetBackends.Entry(readId(b),b.readUtf(64),b.readBoolean()));
            return new Menu(target,token,entries,readId(b),b.readBoolean(),b.readUtf(192));
        });
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Launch(CabinetTarget target,ResourceLocation backend,UUID lease) implements CustomPacketPayload {
        public Launch {Objects.requireNonNull(target);checkedId(backend);Objects.requireNonNull(lease);}
        public static final Type<Launch> TYPE=new Type<>(id("cabinet_launch"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Launch> CODEC=StreamCodec.of((b,p)->{
            writeTarget(b,p.target());writeId(b,p.backend());b.writeUUID(p.lease());
        },b->new Launch(readTarget(b),readId(b),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Closed(UUID lease,String reason) implements CustomPacketPayload {
        public Closed {Objects.requireNonNull(lease);if(reason==null||reason.length()>128)throw new IllegalArgumentException("Close message too long");}
        public static final Type<Closed> TYPE=new Type<>(id("cabinet_closed"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Closed> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.lease());b.writeUtf(p.reason(),128);
        },b->new Closed(b.readUUID(),b.readUtf(128)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Choose(UUID token,ResourceLocation backend) implements CustomPacketPayload {
        public Choose {Objects.requireNonNull(token);checkedId(backend);}
        public static final Type<Choose> TYPE=new Type<>(id("cabinet_choose"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Choose> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.token());writeId(b,p.backend());
        },b->new Choose(b.readUUID(),readId(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Heartbeat(UUID lease) implements CustomPacketPayload {
        public Heartbeat {Objects.requireNonNull(lease);}
        public static final Type<Heartbeat> TYPE=new Type<>(id("cabinet_heartbeat"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Heartbeat> CODEC=StreamCodec.of((b,p)->b.writeUUID(p.lease()),b->new Heartbeat(b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Release(UUID lease) implements CustomPacketPayload {
        public Release {Objects.requireNonNull(lease);}
        public static final Type<Release> TYPE=new Type<>(id("cabinet_release"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Release> CODEC=StreamCodec.of((b,p)->b.writeUUID(p.lease()),b->new Release(b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
