package cn.piq.flashbox.net;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Prototype sends only a hardware menu grant; never paths, SWFs, inputs or media. */
public final class FlashBoxNetwork {
    private FlashBoxNetwork() {}
    public interface Client {
        boolean acceptsConnection(Object connection);
        void open(Open message);
    }
    private static Client client;
    public static void client(Client handler) { client = Objects.requireNonNull(handler); }
    public static void register(IEventBus bus) { bus.addListener(FlashBoxNetwork::registerPayloads); }
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("flash-box-prototype-1").playToClient(Open.TYPE, Open.CODEC, (message, context) -> {
            Object source = context.connection();
            context.enqueueWork(() -> {
                if (client != null && client.acceptsConnection(source)) client.open(message);
            });
        });
    }
    public record Open(ResourceLocation dimension, BlockPos console, UUID consoleId,
                       BlockPos television, UUID televisionId, UUID linkId) implements CustomPacketPayload {
        public Open {
            Objects.requireNonNull(dimension); Objects.requireNonNull(console); Objects.requireNonNull(consoleId);
            Objects.requireNonNull(television); Objects.requireNonNull(televisionId); Objects.requireNonNull(linkId);
            console = console.immutable(); television = television.immutable();
        }
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("piq_flash_box", "open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of((buffer, p) -> {
            buffer.writeResourceLocation(p.dimension); buffer.writeBlockPos(p.console); buffer.writeUUID(p.consoleId);
            buffer.writeBlockPos(p.television); buffer.writeUUID(p.televisionId); buffer.writeUUID(p.linkId);
        }, buffer -> new Open(buffer.readResourceLocation(), buffer.readBlockPos(), buffer.readUUID(),
                buffer.readBlockPos(), buffer.readUUID(), buffer.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
