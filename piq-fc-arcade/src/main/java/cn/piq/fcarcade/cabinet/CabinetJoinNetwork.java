package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;
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

/** Additive consent protocol. No client-selected world target, seat assignment or input authority. */
public final class CabinetJoinNetwork {
    private CabinetJoinNetwork() {}
    public interface ClientSink {
        boolean acceptsConnection(Connection source);
        void offer(Offer value);
        void approval(Approval value);
        void result(Result value);
    }
    private static volatile ClientSink clientSink;
    public static void setClientSink(ClientSink sink) { clientSink = Objects.requireNonNull(sink); }
    public static void send(CustomPacketPayload payload) { PacketDistributor.sendToServer(payload); }
    public static void register(RegisterPayloadHandlersEvent event) {
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cabinet-join-2")
                .playToServer(Allow.TYPE, Allow.CODEC, (p,c) -> server(c, s -> CabinetRooms.allow(s,p)))
                .playToServer(Decision.TYPE, Decision.CODEC, (p,c) -> server(c, s -> CabinetRooms.decide(s,p)))
                .playToClient(Offer.TYPE, Offer.CODEC, (p,c) -> client(c, s -> s.offer(p)))
                .playToClient(Approval.TYPE, Approval.CODEC, (p,c) -> client(c, s -> s.approval(p)))
                .playToClient(Result.TYPE, Result.CODEC, (p,c) -> client(c, s -> s.result(p)));
    }
    private static void server(IPayloadContext context, Consumer<ServerPlayer> action) {
        Connection source = context.connection();
        if (!(context.player() instanceof ServerPlayer player)) return;
        context.enqueueWork(() -> {
            if (source != null && source.isConnected() && player.connection.getConnection() == source) action.accept(player);
        });
    }
    private static void client(IPayloadContext context, Consumer<ClientSink> action) {
        Connection source = context.connection();
        context.enqueueWork(() -> {
            var sink = clientSink;
            if (source != null && sink != null && sink.acceptsConnection(source)) action.accept(sink);
        });
    }
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "cabinet_join_" + name);
    }
    private static void ids(UUID... values) { for (UUID value : values) Objects.requireNonNull(value); }
    private static void text(String value, int limit) {
        if (value == null || value.length() > limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid cabinet join text");
    }
    public record Offer(UUID room, UUID hostMember, UUID token) implements CustomPacketPayload {
        public Offer { ids(room, hostMember, token); }
        public static final Type<Offer> TYPE = new Type<>(id("offer"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Offer> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.room); b.writeUUID(p.hostMember); b.writeUUID(p.token); },
                b -> new Offer(b.readUUID(), b.readUUID(), b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Allow(UUID room, UUID hostMember, UUID token, boolean enabled) implements CustomPacketPayload {
        public Allow { ids(room, hostMember, token); }
        public static final Type<Allow> TYPE = new Type<>(id("allow"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Allow> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.room); b.writeUUID(p.hostMember); b.writeUUID(p.token); b.writeBoolean(p.enabled); },
                b -> new Allow(b.readUUID(), b.readUUID(), b.readUUID(), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Approval(UUID room, UUID hostMember, UUID token, UUID applicant, String applicantName, int port) implements CustomPacketPayload {
        public Approval {
            ids(room, hostMember, token, applicant); text(applicantName,64);
            if (port < 0 || port > 3) throw new IllegalArgumentException("Invalid applicant seat");
        }
        public static final Type<Approval> TYPE = new Type<>(id("approval"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Approval> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.room); b.writeUUID(p.hostMember); b.writeUUID(p.token); b.writeUUID(p.applicant); b.writeUtf(p.applicantName,64); b.writeVarInt(p.port); },
                b -> new Approval(b.readUUID(), b.readUUID(), b.readUUID(), b.readUUID(), b.readUtf(64), b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Decision(UUID room, UUID hostMember, UUID token, boolean accepted) implements CustomPacketPayload {
        public Decision { ids(room, hostMember, token); }
        public static final Type<Decision> TYPE = new Type<>(id("decision"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Decision> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.room); b.writeUUID(p.hostMember); b.writeUUID(p.token); b.writeBoolean(p.accepted); },
                b -> new Decision(b.readUUID(), b.readUUID(), b.readUUID(), b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Result(UUID room, UUID token, String reason) implements CustomPacketPayload {
        public Result { ids(room, token); text(reason,160); }
        public static final Type<Result> TYPE = new Type<>(id("result"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Result> CODEC = StreamCodec.of(
                (b,p) -> { b.writeUUID(p.room); b.writeUUID(p.token); b.writeUtf(p.reason,160); },
                b -> new Result(b.readUUID(), b.readUUID(), b.readUtf(160)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
