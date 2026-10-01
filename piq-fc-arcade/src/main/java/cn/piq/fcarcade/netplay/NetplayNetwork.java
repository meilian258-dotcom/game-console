package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.ArcadeSessionPayload;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class NetplayNetwork {
    private NetplayNetwork(){}
    public interface Sink {void state(Connection source,State value);void data(Connection source,Data value);default void gun(Connection source,GunFrame value){}}
    private static volatile Sink sink;
    private static final java.util.concurrent.atomic.AtomicLong ADDON_IDS=new java.util.concurrent.atomic.AtomicLong(1L<<50);
    private static final Map<Key,NetplayProcess> CLIENTS=new ConcurrentHashMap<>();
    public interface PersistenceFactory {NetplayProcess.Persistence create(Connection source,NetplayProcess.Grant grant);}
    private static volatile PersistenceFactory persistenceFactory;
    public static void persistenceFactory(PersistenceFactory factory){persistenceFactory=Objects.requireNonNull(factory);}
    /** Server-owned namespace distinct from legacy FC session counters. */
    public static long nextAddonId(){long id=ADDON_IDS.incrementAndGet();if(id<=0)throw new IllegalStateException("Netplay ID exhausted");return id;}
    public static void bind(Connection source,NetplayProcess process){
        var key=new Key(source,process.grant().session());if(CLIENTS.putIfAbsent(key,process)!=null)throw new IllegalStateException("Netplay already bound");
        try{var factory=persistenceFactory;if(process.grant().host()&&factory!=null)process.persistence(factory.create(source,process.grant()));}
        catch(RuntimeException failed){CLIENTS.remove(key,process);throw failed;}
    }
    public static void unbind(Connection source,NetplayProcess process){CLIENTS.remove(new Key(source,process.grant().session()),process);}
    /** Client presentation only; a listed process cannot expand its host/ticket authority. */
    public static List<NetplayProcess> clientRuns(Connection source){return CLIENTS.entrySet().stream().filter(e->e.getKey().host()==source).sorted(Comparator.comparingLong(e->e.getKey().session())).map(Map.Entry::getValue).toList();}
    private record Key(Connection host,long session){}
    // Each connection/session maps to exactly one server-owned room, including host.
    private static final Map<Key,NetplayRelay<Connection>> ROUTES=new ConcurrentHashMap<>();
    public static void sink(Sink value){sink=Objects.requireNonNull(value);}
    public static NetplayRelay<Connection> room(long id,Connection host){
        return room(id,host,2);
    }
    public static NetplayRelay<Connection> room(long id,Connection host,int capacity){
        var room=new NetplayRelay<Connection>(id,host,(target,d)->send(target,new Data(d.chunk(),d.port()),true),capacity);
        ROUTES.put(new Key(host,id),room);return room;
    }
    public static void authorize(long id,Connection source,NetplayRelay<Connection> room){ROUTES.put(new Key(source,id),room);}
    public static void prune(NetplayRelay<Connection> room,Set<Connection> allowed){ROUTES.entrySet().removeIf(e->e.getValue()==room&&!allowed.contains(e.getKey().host()));}
    public static void retire(NetplayRelay<Connection> room){ROUTES.entrySet().removeIf(e->e.getValue()==room);room.close();}
    public static void state(ServerPlayer player,State state){send(player.connection.getConnection(),state,true);}
    public static void gun(ServerPlayer player,GunFrame input){send(player.connection.getConnection(),input,true);}
    public static void upstream(Connection source,NetplayChunk chunk){send(source,new Data(chunk,false),false);}
    private static void send(Connection connection,CustomPacketPayload payload,boolean clientbound){
        if(connection==null||!connection.isConnected())return;
        if(payload instanceof Data data){NetplayTransport.send(connection,data,clientbound);return;}
        if(clientbound)connection.send(new ClientboundCustomPayloadPacket(payload));else connection.send(new ServerboundCustomPayloadPacket(payload));
    }
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","netplay_"+path);}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var registrar=event.registrar("netplay-5");
        var measured=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"netplay-5");
        measured.playToClient(State.TYPE,State.CODEC,(packet,ctx)->{Connection source=ctx.connection();ctx.enqueueWork(()->{var current=sink;if(source.isConnected()&&current!=null)current.state(source,packet);});});
        measured.playToClient(GunFrame.TYPE,GunFrame.CODEC,(packet,ctx)->{Connection source=ctx.connection();ctx.enqueueWork(()->{var current=sink;if(source.isConnected()&&current!=null)current.gun(source,packet);});});
        registrar.executesOn(HandlerThread.NETWORK).playBidirectional(Data.TYPE,Data.CODEC,(packet,ctx)->{
            Connection source=ctx.connection();if(!source.isConnected())return;
            if(!source.isMemoryConnection()){
                if(ctx.flow().isServerbound())cn.piq.fcarcade.network.ServerTrafficMeter.netplay(false,packet.encodedBytes());
                else cn.piq.fcarcade.network.ModTrafficProbe.endpoint(source,false,packet.encodedBytes());
            }
            if(ctx.flow().isServerbound()){
                var room=ROUTES.get(new Key(source,packet.chunk.session()));if(room!=null)room.receive(source,packet.chunk);
            }else{var process=CLIENTS.get(new Key(source,packet.chunk.session()));if(process!=null)process.receive(packet.chunk,packet.port);else{var current=sink;if(current!=null)current.data(source,packet);}}
        });
    }
    public record State(ArcadeSessionPayload session,UUID ticket,boolean player,boolean jniTrial) implements CustomPacketPayload {
        public State(ArcadeSessionPayload session,UUID ticket,boolean player){this(session,ticket,player,false);}
        public State {Objects.requireNonNull(session);Objects.requireNonNull(ticket);if(!session.active()||!session.homeRuntime()||session.playerMedia()||session.variant().isZapper()&&player&&!session.computeHost())throw new IllegalArgumentException("Netplay session");}
        public static final Type<State> TYPE=new Type<>(id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{ArcadeSessionPayload.STREAM_CODEC.encode(b,p.session);b.writeUUID(p.ticket);b.writeBoolean(p.player);b.writeBoolean(p.jniTrial);},b->new State(ArcadeSessionPayload.STREAM_CODEC.decode(b),b.readUUID(),b.readBoolean(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Clientbound only: session/epoch/ticket bound, never accepted as a player request. */
    public record GunFrame(long session,UUID ticket,int epoch,long revision,long sequence,int buttons,int aim) implements CustomPacketPayload {
        public GunFrame {Objects.requireNonNull(ticket);if(session<0||epoch<1||revision<0||sequence<0)throw new IllegalArgumentException("Gun authority");new NetplayGunMailbox.Sample(buttons,aim);}
        public static final Type<GunFrame> TYPE=new Type<>(id("gun"));
        public static final StreamCodec<RegistryFriendlyByteBuf,GunFrame> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeUUID(p.ticket);b.writeVarInt(p.epoch);b.writeVarLong(p.revision);b.writeVarLong(p.sequence);b.writeByte(p.buttons);b.writeInt(p.aim);},b->new GunFrame(b.readLong(),b.readUUID(),b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readUnsignedByte(),b.readInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Data(NetplayChunk chunk,int port) implements CustomPacketPayload {
        public Data(NetplayChunk chunk,boolean player){this(chunk,player?1:-1);}
        public boolean player(){return port>=0;}
        public Data {Objects.requireNonNull(chunk);if(port< -1||port>3)throw new IllegalArgumentException("Netplay port");}
        /** Exact CODEC body length, excludes payload id, MC framing and compression. */
        public int encodedBytes(){return 26+varBytes(chunk.sequence())+varBytes(chunk.byteLength())+chunk.byteLength();}
        private static int varBytes(long value){int n=1;while((value>>>=7)!=0)n++;return n;}
        public static final Type<Data> TYPE=new Type<>(id("data"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Data> CODEC=StreamCodec.of((b,p)->{var c=p.chunk;b.writeLong(c.session());b.writeUUID(c.ticket());b.writeByte(c.kind());b.writeVarLong(c.sequence());b.writeByteArray(c.bytes());b.writeByte(p.port);},b->new Data(new NetplayChunk(b.readLong(),b.readUUID(),b.readUnsignedByte(),b.readVarLong(),b.readByteArray(NetplayChunk.LIMIT)),b.readByte()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
