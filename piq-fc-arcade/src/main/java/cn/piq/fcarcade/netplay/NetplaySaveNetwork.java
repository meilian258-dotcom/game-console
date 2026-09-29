// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.*;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler;

/** A separate bounded checkpoint lane, never mixed into RetroArch's authenticated peer stream. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class NetplaySaveNetwork {
    public static final int READ=0,LOAD=1,DOWNLOAD=2,NEXT=3,EMPTY=4,BEGIN=5,READY=6,UPLOAD=7,ACK=8,SAVED=9,DISABLED=10,ERROR=11,FINISH=12,DONE=13,CANCEL=14;
    private NetplaySaveNetwork(){}
    public interface Sink {void receive(Connection source,Message message);}
    private static volatile Sink sink;
    public static void sink(Sink value){sink=Objects.requireNonNull(value);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var reg=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"netplay-save-1");
        // NeoForge keys PLAY registrations by ID, not by direction. Register once;
        // retain the default main-thread dispatch and the existing server authority.
        reg.playBidirectional(Message.TYPE,Message.CODEC,new DirectionalPayloadHandler<>(
            (m,ctx)->{
                cn.piq.fcarcade.network.ModTrafficProbe.endpoint(ctx.connection(),false,m.encodedBytes());
                var s=sink;if(s!=null)s.receive(ctx.connection(),m);
            },
            (m,ctx)->{
                cn.piq.fcarcade.network.ServerTrafficMeter.netplay(false,m.encodedBytes());
                if(ctx.player() instanceof net.minecraft.server.level.ServerPlayer p)NetplaySaveServer.receive(p,m);
            }));
    }
    public static void send(Connection c,Message message,boolean toClient){
        if(c==null||!c.isConnected())throw new IllegalStateException("存档连接已断开");
        if(c.channel()==null||!c.channel().isWritable())throw new IllegalStateException("连接拥塞，本次存档未确认，请稍后重试");
        if(toClient){
            c.send(new ClientboundCustomPayloadPacket(message));
            cn.piq.fcarcade.network.ServerTrafficMeter.netplay(true,message.encodedBytes());
        }else{
            c.send(new ServerboundCustomPayloadPacket(message));
            cn.piq.fcarcade.network.ModTrafficProbe.endpoint(c,true,message.encodedBytes());
        }
    }
    public record Message(long session,UUID ticket,UUID transaction,int kind,int value,int offset,byte[] bytes,String text) implements CustomPacketPayload {
        public Message {
            if(session<0||ticket==null||transaction==null||kind<0||kind>CANCEL||value<0||value>NetplaySaveTransfer.MAX_PACKED
                    ||offset<0||offset>NetplaySaveTransfer.MAX_PACKED||bytes==null||bytes.length>NetplaySaveTransfer.CHUNK||text==null||text.length()>256)
                throw new IllegalArgumentException("Netplay 保存消息无效");
            bytes=bytes.clone();
            boolean chunk=kind==DOWNLOAD||kind==UPLOAD;
            if(chunk!=(bytes.length>0)||!chunk&&offset!=0&&kind!=NEXT&&kind!=ACK
                    ||kind!=LOAD&&kind!=BEGIN&&kind!=DOWNLOAD&&value!=0
                    ||(kind==LOAD||kind==BEGIN||kind==DOWNLOAD)&&value<1
                    ||kind!=READ&&kind!=EMPTY&&kind!=DISABLED&&kind!=ERROR&&kind!=SAVED&&kind!=DONE&&!text.isEmpty())
                throw new IllegalArgumentException("Netplay 保存消息字段无效");
        }
        @Override public byte[] bytes(){return bytes.clone();}
        /** Exact CODEC body length, excluding the payload ID, MC framing and compression. */
        public int encodedBytes(){
            int textBytes=ByteBufUtil.utf8Bytes(text);
            return 41+VarInt.getByteSize(value)+VarInt.getByteSize(offset)
                    +VarInt.getByteSize(bytes.length)+bytes.length+VarInt.getByteSize(textBytes)+textBytes;
        }
        public static final Type<Message> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","netplay_save"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Message> CODEC=StreamCodec.of((b,p)->{
            b.writeLong(p.session);b.writeUUID(p.ticket);b.writeUUID(p.transaction);b.writeByte(p.kind);b.writeVarInt(p.value);b.writeVarInt(p.offset);b.writeByteArray(p.bytes);b.writeUtf(p.text,256);
        },b->new Message(b.readLong(),b.readUUID(),b.readUUID(),b.readUnsignedByte(),b.readVarInt(),b.readVarInt(),b.readByteArray(NetplaySaveTransfer.CHUNK),b.readUtf(256)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
