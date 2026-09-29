package cn.piq.fcarcade.server;

import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.cabinet.*;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** S2C-only media lane; controls still use the exact existing FC physical lease protocol. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class FcHomeHostedNetwork {
    private FcHomeHostedNetwork(){}
    public interface ClientSink {boolean accepts(Connection connection);void state(State state);void stream(Stream stream);}
    private static volatile ClientSink sink;
    public static void clientSink(ClientSink value){sink=Objects.requireNonNull(value);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"fc-home-hosted-2")
            .playToClient(State.TYPE,State.CODEC,(p,c)->{var source=c.connection();c.enqueueWork(()->{var current=sink;if(source!=null&&source.isConnected()&&current!=null&&current.accepts(source))current.state(p);});})
            .playToClient(Stream.TYPE,Stream.CODEC,(p,c)->{var source=c.connection();c.enqueueWork(()->{var current=sink;if(source!=null&&source.isConnected()&&current!=null&&current.accepts(source))current.stream(p);});});
    }
    public static void sendState(ServerPlayer player,State state){PacketDistributor.sendToPlayer(player,state);}
    public static boolean send(ServerPlayer player,long session,int epoch,List<CabinetMediaPacket> batch){
        var packets=new ArrayList<Stream>(batch.size());int[] bytes=new int[batch.size()];int at=0;
        for(var part:batch){packets.add(new Stream(session,epoch,player.getUUID(),new CabinetRoomNetwork.Media(part.room(),part.hostMember(),part.sequence(),part.kind(),part.index(),part.count(),part.width(),part.height(),part.aspect(),part.rotation(),part.rawLength(),part.data())));bytes[at++]=part.data().length+320;}
        return CabinetMediaSender.sendPayloads(player.connection.getConnection(),packets,bytes,false);
    }
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_hosted_"+path);}
    public record State(ArcadeSessionPayload session,UUID source,UUID token,boolean operator,boolean playerMediaReceiver) implements CustomPacketPayload {
        public State(ArcadeSessionPayload session,UUID source,UUID token,boolean operator){this(session,source,token,operator,false);}
        public State {Objects.requireNonNull(session);Objects.requireNonNull(source);Objects.requireNonNull(token);if(session.active()&&(!session.homeRuntime()||session.computeHost())||playerMediaReceiver!=session.playerMedia()||playerMediaReceiver&&operator)throw new IllegalArgumentException("Hosted FC authority");}
        public static final Type<State> TYPE=new Type<>(id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{ArcadeSessionPayload.STREAM_CODEC.encode(b,p.session);b.writeUUID(p.source);b.writeUUID(p.token);b.writeBoolean(p.operator);b.writeBoolean(p.playerMediaReceiver);},b->new State(ArcadeSessionPayload.STREAM_CODEC.decode(b),b.readUUID(),b.readUUID(),b.readBoolean(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Stream(long session,int epoch,UUID recipient,CabinetRoomNetwork.Media media) implements CustomPacketPayload {
        public Stream {Objects.requireNonNull(recipient);Objects.requireNonNull(media);if(session<0||epoch<1)throw new IllegalArgumentException("Hosted FC stream identity");}
        public static final Type<Stream> TYPE=new Type<>(id("stream"));
        public boolean matches(long activeSession,int activeEpoch,UUID source,UUID token,UUID player){return session==activeSession&&epoch==activeEpoch&&recipient.equals(player)&&media.room().equals(source)&&media.hostMember().equals(token);}
        public static final StreamCodec<RegistryFriendlyByteBuf,Stream> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.recipient);CabinetRoomNetwork.Media.CODEC.encode(b,p.media);},b->new Stream(b.readLong(),b.readVarInt(),b.readUUID(),CabinetRoomNetwork.Media.CODEC.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
