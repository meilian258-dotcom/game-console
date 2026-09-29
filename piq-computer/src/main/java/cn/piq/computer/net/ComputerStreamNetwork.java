package cn.piq.computer.net;

import cn.piq.computer.stream.StreamPart;
import cn.piq.computer.world.ComputerStreamServer;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ComputerStreamNetwork {
    public static final UUID NONE=new UUID(0,0);
    public interface Client { boolean accepts(Object connection);void status(Status s);void media(Media m);void input(Input i); }
    public static Client client;
    public static void register(IEventBus bus){bus.addListener(ComputerStreamNetwork::payloads);}
    private static void payloads(RegisterPayloadHandlersEvent e){
        var r=e.registrar("computer-stream-1");
        r.playToServer(Action.TYPE,Action.CODEC,(m,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p)ComputerStreamServer.action(p,m);}));
        r.playBidirectional(Media.TYPE,Media.CODEC,(m,c)->{var connection=c.connection();c.enqueueWork(()->{
            if(c.player() instanceof ServerPlayer p)ComputerStreamServer.media(p,m);
            else if(client!=null&&client.accepts(connection))client.media(m);
        });});
        r.playToClient(Status.TYPE,Status.CODEC,(m,c)->{var connection=c.connection();c.enqueueWork(()->{if(client!=null&&client.accepts(connection))client.status(m);});});
        r.playToClient(Input.TYPE,Input.CODEC,(m,c)->{var connection=c.connection();c.enqueueWork(()->{if(client!=null&&client.accepts(connection))client.input(m);});});
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_computer","stream_"+name);}
    public record Key(ResourceLocation dimension,BlockPos pos,UUID computer){
        public void write(RegistryFriendlyByteBuf b){b.writeResourceLocation(dimension);b.writeBlockPos(pos);b.writeUUID(computer);}
        public static Key read(RegistryFriendlyByteBuf b){return new Key(b.readResourceLocation(),b.readBlockPos(),b.readUUID());}
    }
    // 0 start, 1 stop, 2 allow handoff, 3 lock handoff, 4 host keepalive.
    public record Action(Key key,int operation,UUID session,int kind,int tier) implements CustomPacketPayload {
        public static final Type<Action> TYPE=new Type<>(id("action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,m)->{m.key.write(b);b.writeByte(m.operation);b.writeUUID(m.session);b.writeByte(m.kind);b.writeByte(m.tier);},b->new Action(Key.read(b),b.readUnsignedByte(),b.readUUID(),b.readUnsignedByte(),b.readUnsignedByte()));
        public Type<Action> type(){return TYPE;}
    }
    public record Status(Key key,UUID session,UUID host,UUID controller,long epoch,boolean handoff,int kind,int tier,int viewers,boolean active) implements CustomPacketPayload {
        public Status{if(epoch<0||kind<0||kind>2||tier<0||tier>2||viewers<0||viewers>8)throw new IllegalArgumentException("Stream status");}
        public static final Type<Status> TYPE=new Type<>(id("status"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Status> CODEC=StreamCodec.of((b,m)->{m.key.write(b);b.writeUUID(m.session);b.writeUUID(m.host);b.writeUUID(m.controller);b.writeLong(m.epoch);b.writeBoolean(m.handoff);b.writeByte(m.kind);b.writeByte(m.tier);b.writeByte(m.viewers);b.writeBoolean(m.active);},b->new Status(Key.read(b),b.readUUID(),b.readUUID(),b.readUUID(),b.readLong(),b.readBoolean(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte(),b.readBoolean()));
        public Type<Status> type(){return TYPE;}
    }
    public record Input(UUID computer,UUID session,long epoch,int kind,int value,int x,int y,int buttons) implements CustomPacketPayload {
        public Input{if(epoch<0||kind<0||kind>5||x<0||x>639||y<0||y>479||buttons<0||buttons>15||value<0||value>0x10ffff)throw new IllegalArgumentException("Stream input");}
        public static final Type<Input> TYPE=new Type<>(id("input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,m)->{b.writeUUID(m.computer);b.writeUUID(m.session);b.writeLong(m.epoch);b.writeByte(m.kind);b.writeInt(m.value);b.writeShort(m.x);b.writeShort(m.y);b.writeByte(m.buttons);},b->new Input(b.readUUID(),b.readUUID(),b.readLong(),b.readUnsignedByte(),b.readInt(),b.readUnsignedShort(),b.readUnsignedShort(),b.readUnsignedByte()));
        public Type<Input> type(){return TYPE;}
    }
    public record Media(StreamPart part) implements CustomPacketPayload {
        public static final Type<Media> TYPE=new Type<>(id("media"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Media> CODEC=StreamCodec.of((b,m)->{var p=m.part;b.writeUUID(p.computer());b.writeUUID(p.session());b.writeLong(p.sequence());b.writeLong(p.micros());b.writeByte(p.kind());b.writeByte(p.index());b.writeByte(p.count());b.writeShort(p.width());b.writeShort(p.height());b.writeByteArray(p.bytes());},b->new Media(new StreamPart(b.readUUID(),b.readUUID(),b.readLong(),b.readLong(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedByte(),b.readUnsignedShort(),b.readUnsignedShort(),b.readByteArray(StreamPart.PART))));
        public Type<Media> type(){return TYPE;}
    }
    private ComputerStreamNetwork(){}
}
