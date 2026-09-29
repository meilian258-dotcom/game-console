package cn.piq.sfchome.net;

import cn.piq.fcarcade.cabinet.*;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server-to-authorized-home-member media lane, for a server core or relayed player host.
 * It grants no input, ROM or watch membership; the session fixes the actual producer. */
public final class SfcHostedNetwork {
    private SfcHostedNetwork(){}
    public record Stream(long session,int epoch,UUID recipient,CabinetRoomNetwork.Media media) implements CustomPacketPayload {
        public Stream{if(session<=0||epoch<=0)throw new IllegalArgumentException("Invalid hosted session");Objects.requireNonNull(recipient);Objects.requireNonNull(media);}
        public static final Type<Stream> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","hosted_stream"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Stream> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.recipient);CabinetRoomNetwork.Media.CODEC.encode(b,p.media);},b->new Stream(b.readVarLong(),b.readVarInt(),b.readUUID(),CabinetRoomNetwork.Media.CODEC.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
        public CabinetMediaPacket packet(){var p=media;return new CabinetMediaPacket(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data());}
    }
    public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"home-hosted-1").playToClient(Stream.TYPE,Stream.CODEC,(p,c)->SfcHomeNetwork.dispatch(c,h->h.hosted(p)));
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"home-hosted-1").playToClient(Reset.TYPE,Reset.CODEC,(p,c)->SfcHomeNetwork.dispatch(c,h->h.hostedReset(p)));
    }
    public record Reset(long session,int epoch,UUID recipient) implements CustomPacketPayload{
        public Reset{if(session<=0||epoch<=0)throw new IllegalArgumentException("Invalid reset session");Objects.requireNonNull(recipient);}
        public static final Type<Reset> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","hosted_reset"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reset> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.recipient);},b->new Reset(b.readVarLong(),b.readVarInt(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static boolean send(net.minecraft.network.Connection connection,long session,int epoch,UUID recipient,List<CabinetMediaPacket> batch){
        var payloads=new ArrayList<Stream>(batch.size());int[] bytes=new int[batch.size()];
        for(int i=0;i<batch.size();i++){var p=batch.get(i);byte[] data=p.data();payloads.add(new Stream(session,epoch,recipient,new CabinetRoomNetwork.Media(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),data)));bytes[i]=data.length+384;}
        return CabinetMediaSender.sendPayloads(connection,payloads,bytes,false);
    }
}
