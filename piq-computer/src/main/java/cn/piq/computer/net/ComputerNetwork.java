package cn.piq.computer.net;

import cn.piq.computer.ComputerMod;
import cn.piq.computer.world.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class ComputerNetwork {
    public interface Client { boolean accepts(Object connection); void open(Open value); }
    public static Client client;
    public static void register(IEventBus bus){bus.addListener(ComputerNetwork::payloads);}
    private static void payloads(RegisterPayloadHandlersEvent e){
        var r=e.registrar("computer-hardware-3");
        r.playToClient(Open.TYPE,Open.CODEC,(m,c)->{var connection=c.connection();c.enqueueWork(()->{if(client!=null&&client.accepts(connection))client.open(m);});});
        r.playToServer(Command.TYPE,Command.CODEC,(m,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p)handle(p,m);}));
    }
    public static void open(ServerPlayer p,ComputerEntity pc,boolean control,UUID token){PacketDistributor.sendToPlayer(p,new Open(p.serverLevel().dimension().location(),pc.getBlockPos(),pc.hardwareId(),control,token));}
    private static void handle(ServerPlayer p,Command m){
        if(!p.serverLevel().dimension().location().equals(m.dimension)||!p.serverLevel().hasChunkAt(m.pos))return;
        var pc=ComputerBlock.find(p.serverLevel(),m.pos);if(pc==null||!pc.getBlockPos().equals(m.pos)||!pc.hardwareId().equals(m.id))return;
        if(m.kind==5){if(pc.lease.release(p.getUUID(),m.token))pc.release();return;}
        if(m.kind==6){if(ComputerAccess.near(p,m.pos)&&ComputerAccess.allowed(p,m.pos))pc.removePart(p,m.value);return;}
        if(m.kind<0||m.kind>4||m.x<0||m.x>639||m.y<0||m.y>479||m.buttons<0||m.buttons>15||m.value<0||m.value>0x10ffff)return;
        if((m.kind==1||m.kind==2)&&m.value>512)return;
        if(m.value>=0xd800&&m.value<=0xdfff)return;
        if(!p.getUUID().equals(pc.operator)||!pc.currentUser(p)||!pc.lease.accept(p.getUUID(),m.token,m.sequence,p.serverLevel().getGameTime()))return;
        if(!ComputerStreamServer.forward(pc,m.kind,m.value,m.x,m.y,m.buttons))pc.input(m.kind,m.value,m.x,m.y,(m.buttons&8)!=0?0:m.buttons&7);
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath(ComputerMod.ID,name);}
    public record Open(ResourceLocation dimension,BlockPos pos,UUID id,boolean control,UUID token) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(ComputerNetwork.id("open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.of((b,m)->{b.writeResourceLocation(m.dimension);b.writeBlockPos(m.pos);b.writeUUID(m.id);b.writeBoolean(m.control);b.writeUUID(m.token);},b->new Open(b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readBoolean(),b.readUUID()));
        @Override public Type<Open> type(){return TYPE;}
    }
    public record Command(ResourceLocation dimension,BlockPos pos,UUID id,UUID token,long sequence,int kind,int value,int x,int y,int buttons) implements CustomPacketPayload {
        public static final Type<Command> TYPE=new Type<>(ComputerNetwork.id("command"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Command> CODEC=StreamCodec.of((b,m)->{b.writeResourceLocation(m.dimension);b.writeBlockPos(m.pos);b.writeUUID(m.id);b.writeUUID(m.token);b.writeLong(m.sequence);b.writeByte(m.kind);b.writeInt(m.value);b.writeInt(m.x);b.writeInt(m.y);b.writeByte(m.buttons);},b->new Command(b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readUUID(),b.readLong(),b.readUnsignedByte(),b.readInt(),b.readInt(),b.readInt(),b.readUnsignedByte()));
        @Override public Type<Command> type(){return TYPE;}
    }
}
