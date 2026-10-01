package cn.piq.gba.item;

import cn.piq.gba.GbaMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;

/** Only small intent packets. The server resolves items; no client paths/ROM/NBT. */
public final class GbaHandheldNetwork {
    public static final int INSERT=0,POWER=1,EJECT=2,OFF=3;
    public record Request(int action,InteractionHand hand,UUID device,UUID nonce) implements CustomPacketPayload {
        public Request{if(action<0||action>OFF||hand==null||device==null||nonce==null)throw new IllegalArgumentException("GBA request");}
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(GbaMod.ID,"handheld_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,m)->{b.writeByte(m.action);b.writeEnum(m.hand);b.writeUUID(m.device);b.writeUUID(m.nonce);},b->new Request(b.readUnsignedByte(),b.readEnum(InteractionHand.class),b.readUUID(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Reply(UUID nonce,UUID token) implements CustomPacketPayload {
        public Reply{if(nonce==null||token==null)throw new IllegalArgumentException("GBA reply");}
        public boolean starting(){return !token.equals(GbaCartridgeSlot.EMPTY_ID);}
        public static final Type<Reply> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(GbaMod.ID,"handheld_reply_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,m)->{b.writeUUID(m.nonce);b.writeUUID(m.token);},b->new Reply(b.readUUID(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent e){
        var registrar=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(e,"gba-handheld-1");
        registrar.playToServer(Request.TYPE,Request.CODEC,(m,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p&&p.connection.getConnection()==c.connection()){
            GbaHandheldServer.handle(p,m);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new Reply(m.nonce(),GbaHandheldServer.playToken(p,m)));
        }}));
        registrar.playToClient(Reply.TYPE,Reply.CODEC,(m,c)->c.enqueueWork(()->Client.receive(m,c.connection())));
    }
    private static final class Client{static void receive(Reply m,net.minecraft.network.Connection connection){var mc=net.minecraft.client.Minecraft.getInstance();if(mc.getConnection()!=null&&mc.getConnection().getConnection()==connection)cn.piq.gba.client.GbaHandheldClient.reply(m);}}
    private GbaHandheldNetwork(){}
}
