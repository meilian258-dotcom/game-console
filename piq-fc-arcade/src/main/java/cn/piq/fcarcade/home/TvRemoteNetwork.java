package cn.piq.fcarcade.home;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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

/** Physical remote intent only; it cannot access saves, ROMs or synchronization modes. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class TvRemoteNetwork {
    private TvRemoteNetwork() {}
    public interface ClientSink { boolean accepts(Connection source); void setting(Setting value); }
    private static volatile ClientSink sink;
    public static void clientSink(ClientSink value) { sink = Objects.requireNonNull(value); }
    public static void request(UUID token, int revision, int action, int value) {
        PacketDistributor.sendToServer(new Request(token, revision, action, value));
    }
    static void send(ServerPlayer player, Setting value) { PacketDistributor.sendToPlayer(player, value); }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"tv-remote-1")
            .playToServer(Request.TYPE, Request.CODEC, (packet, context) -> {
                Connection source = context.connection(); var entity = context.player();
                context.enqueueWork(() -> {
                    if (entity instanceof ServerPlayer player && source != null && source.isConnected()
                        && player.connection.getConnection() == source && !player.hasDisconnected()
                        && player.getServer() != null && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player)
                        TvRemoteService.request(player, packet);
                });
            })
            .playToClient(Setting.TYPE, Setting.CODEC, (packet, context) -> {
                Connection source = context.connection();
                context.enqueueWork(() -> { var current = sink;
                    if (source != null && source.isConnected() && current != null && current.accepts(source)) current.setting(packet);
                });
            });
    }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade", "tv_remote_" + name); }
    public record Request(UUID token, int revision, int action, int value) implements CustomPacketPayload {
        public Request { Objects.requireNonNull(token); if (revision < 0 || !TvRemoteSettingsPolicy.valid(action, value)) throw new IllegalArgumentException("Remote request"); }
        public static final Type<Request> TYPE = new Type<>(id("request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
            (b,p) -> { b.writeUUID(p.token); b.writeVarInt(p.revision); b.writeVarInt(p.action); b.writeVarInt(p.value); },
            b -> new Request(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Setting(UUID token, int revision, ResourceLocation dimension, BlockPos television, UUID hardware,
                          boolean scanlines, int volume, boolean muted, boolean animation, boolean tone,
                          boolean occupancy, boolean approval, boolean hasConsole, boolean administrator,
                          String reason, boolean open) implements CustomPacketPayload {
        public Setting {
            Objects.requireNonNull(token); Objects.requireNonNull(dimension); Objects.requireNonNull(hardware);
            television = Objects.requireNonNull(television).immutable();
            if (revision < 0 || volume < 0 || volume > 100 || reason == null || reason.length() > 256) throw new IllegalArgumentException("Remote setting");
        }
        public static final Type<Setting> TYPE = new Type<>(id("setting"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Setting> CODEC = StreamCodec.of((b,p) -> {
            b.writeUUID(p.token); b.writeVarInt(p.revision); b.writeResourceLocation(p.dimension); b.writeBlockPos(p.television); b.writeUUID(p.hardware);
            b.writeBoolean(p.scanlines); b.writeVarInt(p.volume); b.writeBoolean(p.muted); b.writeBoolean(p.animation); b.writeBoolean(p.tone);
            b.writeBoolean(p.occupancy); b.writeBoolean(p.approval); b.writeBoolean(p.hasConsole); b.writeBoolean(p.administrator);
            b.writeUtf(p.reason,256); b.writeBoolean(p.open);
        }, b -> new Setting(b.readUUID(),b.readVarInt(),b.readResourceLocation(),b.readBlockPos(),b.readUUID(),
            b.readBoolean(),b.readVarInt(),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean(),
            b.readBoolean(),b.readBoolean(),b.readUtf(256),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
