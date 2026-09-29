package cn.piq.fcarcade.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registration adapter only: versions, payload bytes, handlers and default threading are unchanged. */
public final class TrafficPayloadRegistrar {
    private final PayloadRegistrar delegate;
    private TrafficPayloadRegistrar(PayloadRegistrar delegate) { this.delegate = delegate; }
    public static TrafficPayloadRegistrar create(RegisterPayloadHandlersEvent event,String version) {
        return new TrafficPayloadRegistrar(event.registrar(version));
    }
    public <T extends CustomPacketPayload> TrafficPayloadRegistrar playToClient(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf,T> codec,IPayloadHandler<T> handler) {
        delegate.playToClient(type,ServerTrafficMeter.wrap(type.id(),true,ModTrafficProbe.toClient(codec,TrafficCategory.of(type.id()))),handler); return this;
    }
    public <T extends CustomPacketPayload> TrafficPayloadRegistrar playToServer(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf,T> codec,IPayloadHandler<T> handler) {
        delegate.playToServer(type,ServerTrafficMeter.wrap(type.id(),false,ModTrafficProbe.toServer(codec,TrafficCategory.of(type.id()))),handler); return this;
    }
    /** Bidirectional lanes measure at explicit endpoints, not stacked directional codec wrappers. */
    public <T extends CustomPacketPayload> TrafficPayloadRegistrar playBidirectional(CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf,T> codec,IPayloadHandler<T> handler) {
        delegate.playBidirectional(type,codec,handler); return this;
    }
}
