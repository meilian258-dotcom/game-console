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

/** Capability-scoped settings packets; none carries power, controller or ROM authority. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class HomeSyncNetwork {
    private HomeSyncNetwork() {}
    public interface ClientSink { boolean accepts(Connection source); void setting(Setting value); }
    private static volatile ClientSink sink;
    public static void clientSink(ClientSink value) { sink = Objects.requireNonNull(value); }
    public static void request(UUID token,int revision,int mode,int occupancy,int approval) {
        PacketDistributor.sendToServer(new Request(token,revision,mode,occupancy,approval));
    }
    static void send(ServerPlayer player,Setting setting) { PacketDistributor.sendToPlayer(player,setting); }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"home-sync-4")
                .playToServer(Request.TYPE,Request.CODEC,(packet,context) -> {
                    Connection source = context.connection();var entity = context.player();
                    context.enqueueWork(() -> {
                        if (entity instanceof ServerPlayer player && source != null && source.isConnected()
                                && player.connection.getConnection() == source && !player.hasDisconnected()
                                && player.getServer() != null && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player)
                            HomeSyncSettings.request(player,packet);
                    });
                })
                .playToClient(Setting.TYPE,Setting.CODEC,(packet,context) -> {
                    Connection source = context.connection();
                    context.enqueueWork(() -> { var current = sink;if (source != null && source.isConnected() && current != null && current.accepts(source)) current.setting(packet); });
                });
    }
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_sync_"+name); }
    public record Request(UUID token,int revision,int mode,int occupancy,int approval) implements CustomPacketPayload {
        public Request { Objects.requireNonNull(token);if (!DeviceDebugPolicy.validRequest(revision,mode,occupancy,approval)) throw new IllegalArgumentException("Home settings request"); }
        public static final Type<Request> TYPE = new Type<>(id("request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC = StreamCodec.of((b,p) -> {
            b.writeUUID(p.token);b.writeVarInt(p.revision);b.writeInt(p.mode);b.writeInt(p.occupancy);b.writeInt(p.approval);
        },b -> new Request(b.readUUID(),b.readVarInt(),b.readInt(),b.readInt(),b.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Setting(UUID token,int revision,ResourceLocation dimension,BlockPos console,UUID hardware,String system,
                          int mode,int supported,boolean editable,String reason,boolean open,
                          boolean occupancy,boolean approval,boolean occupancySupported,boolean debugTool) implements CustomPacketPayload {
        public Setting {
            Objects.requireNonNull(token);Objects.requireNonNull(dimension);console = Objects.requireNonNull(console).immutable();Objects.requireNonNull(hardware);
            if (revision < 0 || mode < 0 || mode > 4 || (supported & ~31) != 0 || system == null || system.length() > 128 || reason == null || reason.length() > 256) throw new IllegalArgumentException("Home setting");
        }
        public static final Type<Setting> TYPE = new Type<>(id("setting"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Setting> CODEC = StreamCodec.of((b,p) -> {
            b.writeUUID(p.token);b.writeVarInt(p.revision);b.writeResourceLocation(p.dimension);b.writeBlockPos(p.console);b.writeUUID(p.hardware);b.writeUtf(p.system,128);
            b.writeVarInt(p.mode);b.writeVarInt(p.supported);b.writeBoolean(p.editable);b.writeUtf(p.reason,256);b.writeBoolean(p.open);
            b.writeBoolean(p.occupancy);b.writeBoolean(p.approval);b.writeBoolean(p.occupancySupported);b.writeBoolean(p.debugTool);
        },b -> new Setting(b.readUUID(),b.readVarInt(),b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readUtf(128),b.readVarInt(),b.readVarInt(),b.readBoolean(),b.readUtf(256),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
