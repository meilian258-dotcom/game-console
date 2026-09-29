package cn.piq.fcarcade.config;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class AdminTerminalNetwork {
    public interface ClientSink { boolean accepts(Connection source); void receive(State state); }
    private static volatile ClientSink sink;
    private AdminTerminalNetwork() {}
    public static void clientSink(ClientSink value){sink=Objects.requireNonNull(value);}
    public static void send(Request value){PacketDistributor.sendToServer(value);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"admin-terminal-2")
            .playToServer(Request.TYPE,Request.CODEC,(packet,context)->{
                var source=context.connection();var actor=context.player();
                context.enqueueWork(()->{
                    if(actor instanceof net.minecraft.server.level.ServerPlayer player && source!=null&&source.isConnected()
                            &&player.connection.getConnection()==source)AdminTerminalService.handle(player,packet);
                });
            }).playToClient(State.TYPE,State.CODEC,(packet,context)->{
                var source=context.connection();context.enqueueWork(()->{
                    var current=sink;if(source!=null&&source.isConnected()&&current!=null&&current.accepts(source))current.receive(packet);
                });
            });
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","admin_terminal_"+name);}
    public record Request(UUID nonce,int hand,int action,int value,int expectedOptions,int expectedMode,int expectedRange,int expectedRetention) implements CustomPacketPayload {
        public Request(UUID nonce,int hand,int action,int value,int options,int mode,int range){this(nonce,hand,action,value,options,mode,range,0);}
        public Request {
            Objects.requireNonNull(nonce);
            if(hand<0||hand>1||expectedRetention<0||expectedRetention>3650||!AdminTerminalPolicy.valid(action,value)||expectedOptions<0||(expectedOptions&~31)!=0
                    ||!GameConsoleAdminPolicy.validMode(expectedMode)||!GameConsoleAdminPolicy.validRange(expectedRange))throw new IllegalArgumentException("Terminal request");
        }
        public static final Type<Request> TYPE=new Type<>(id("request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.nonce);b.writeByte(p.hand);b.writeVarInt(p.action);b.writeInt(p.value);
            b.writeByte(p.expectedOptions);b.writeInt(p.expectedMode);b.writeVarInt(p.expectedRange);b.writeVarInt(p.expectedRetention);
        },b->new Request(b.readUUID(),b.readUnsignedByte(),b.readVarInt(),b.readInt(),b.readUnsignedByte(),b.readInt(),b.readVarInt(),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record State(UUID nonce,boolean editable,int options,int mode,int range,int supported,String message,int retention) implements CustomPacketPayload {
        public State(UUID nonce,boolean editable,int options,int mode,int range,int supported,String message){this(nonce,editable,options,mode,range,supported,message,0);}
        public State {
            Objects.requireNonNull(nonce);Objects.requireNonNull(message);
            if(retention<0||retention>3650||options<0||(options&~31)!=0||!GameConsoleAdminPolicy.validMode(mode)||!GameConsoleAdminPolicy.validRange(range)
                    ||supported<0||(supported&~7)!=0||message.length()>192)throw new IllegalArgumentException("Terminal state");
        }
        public static final Type<State> TYPE=new Type<>(id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.nonce);b.writeBoolean(p.editable);b.writeByte(p.options);b.writeInt(p.mode);b.writeVarInt(p.range);b.writeByte(p.supported);b.writeUtf(p.message,192);b.writeVarInt(p.retention);
        },b->new State(b.readUUID(),b.readBoolean(),b.readUnsignedByte(),b.readInt(),b.readVarInt(),b.readUnsignedByte(),b.readUtf(192),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
