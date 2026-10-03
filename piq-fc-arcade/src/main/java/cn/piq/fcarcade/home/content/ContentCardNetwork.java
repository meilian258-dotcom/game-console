package cn.piq.fcarcade.home.content;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** One bounded bidirectional registration; direction is dispatched by the registrar. */
public final class ContentCardNetwork {
    public static final int OPEN=0,LIST=1,UPLOAD=2,PART=3,WRITE=4,CANCEL=5,STATUS=6,READY=7,
            DOWNLOAD=8,GET=9,DATA=10,STARTED=11,STOP=12,HEARTBEAT=13,RENAME=14,CARD=15,RESET=16,
            COVER_LIST=17,COVER_WRITE=18,COVER_UPLOAD=19,SAVE_MODE=20,PLAYERS=21,CARD_OPTIONS=22,SAVE_LIBRARY=23,DOWNLOAD_ONLY=24;
    public record Message(int op,ResourceLocation system,UUID token,BlockPos pos,String hash,String name,
                          int size,int offset,byte[] data,List<ContentCardStore.Entry> entries) implements CustomPacketPayload {
        public Message {
            if(op<0||op>DOWNLOAD_ONLY||system==null||token==null||pos==null||hash==null||(!hash.isEmpty()&&!hash.matches("[0-9a-f]{64}"))
                    ||name==null||name.length()>256||name.chars().anyMatch(Character::isISOControl)
                    ||size<0||size>ContentCardStore.MAX_BYTES||offset<0||offset>ContentCardStore.MAX_BYTES
                    ||data==null||data.length>ContentCardStore.CHUNK||entries==null||entries.size()>8)throw new IllegalArgumentException("Invalid content-card message");
            data=data.clone();entries=List.copyOf(entries);pos=pos.immutable();
        }
        @Override public byte[] data(){return data.clone();}
        public static final Type<Message> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","content_card_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Message> CODEC=StreamCodec.of((b,m)->{
            b.writeByte(m.op);b.writeResourceLocation(m.system);b.writeUUID(m.token);b.writeBlockPos(m.pos);b.writeUtf(m.hash,64);b.writeUtf(m.name,256);
            b.writeVarInt(m.size);b.writeVarInt(m.offset);b.writeByteArray(m.data);b.writeVarInt(m.entries.size());
            for(var e:m.entries){b.writeUtf(e.hash(),64);b.writeUtf(e.name(),128);b.writeVarInt(e.size());b.writeUtf(e.displayName(),128);}
        },b->{
            int op=b.readUnsignedByte();var system=b.readResourceLocation();var token=b.readUUID();var pos=b.readBlockPos();
            var hash=b.readUtf(64);var name=b.readUtf(256);int size=b.readVarInt(),offset=b.readVarInt();var data=b.readByteArray(ContentCardStore.CHUNK);
            int n=b.readVarInt();if(n<0||n>8)throw new IllegalArgumentException("Card page too large");
            var list=new ArrayList<ContentCardStore.Entry>();for(int i=0;i<n;i++)list.add(new ContentCardStore.Entry(b.readUtf(64),b.readUtf(128),b.readVarInt(),b.readUtf(128)));
            return new Message(op,system,token,pos,hash,name,size,offset,data,list);
        });
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static Message msg(int op,ResourceLocation system,UUID token,BlockPos pos,String hash,String name,int size,int offset,byte[] data){return new Message(op,system,token,pos,hash,name,size,offset,data,List.of());}
    public static boolean clientbound(int op){return switch(op){
        case OPEN,LIST,CANCEL,STATUS,READY,DOWNLOAD,DATA,STOP,CARD,RESET,COVER_LIST,CARD_OPTIONS,DOWNLOAD_ONLY->true;
        default->false;
    };}
    public static boolean serverbound(int op){return switch(op){
        case LIST,UPLOAD,PART,WRITE,CANCEL,GET,STARTED,STOP,HEARTBEAT,RENAME,COVER_LIST,COVER_WRITE,COVER_UPLOAD,SAVE_MODE,PLAYERS,SAVE_LIBRARY->true;
        default->false;
    };}
    public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"content-card-6").playBidirectional(Message.TYPE,Message.CODEC,
            (m,c)->{if(c.flow().isClientbound()?!clientbound(m.op()):!serverbound(m.op()))return;c.enqueueWork(()->{
                if(c.flow().isClientbound())Client.receive(m,c.connection());
                else if(c.player() instanceof ServerPlayer p)ContentCards.handle(p,m);
            });});
    }
    private static final class Client {
        static void receive(Message m,net.minecraft.network.Connection connection){
            var current=net.minecraft.client.Minecraft.getInstance().getConnection();
            if(current!=null&&current.getConnection()==connection)cn.piq.fcarcade.client.ContentCardClient.receive(m);
        }
    }
    public static void send(Message m){PacketDistributor.sendToServer(m);}
    public static void send(ServerPlayer p,Message m){PacketDistributor.sendToPlayer(p,m);}
    private ContentCardNetwork(){}
}
