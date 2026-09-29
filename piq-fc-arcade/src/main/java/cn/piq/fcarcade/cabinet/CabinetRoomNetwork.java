package cn.piq.fcarcade.cabinet;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Fixed-seat host relay. World coordinates are never accepted in a client authority request. */
public final class CabinetRoomNetwork {
    private CabinetRoomNetwork() {}
    public interface ClientSink {
        boolean acceptsConnection(Connection connection);
        void assignment(Assignment value);
        void seat(Seat value);
        void buttons(Buttons value);
        void media(Stream value);
        default void moderator(Moderator value){}
        default void coin(Coin value){}
        default void netplay(NetplayStart value){}
        default void pgmService(PgmService value){}
        default void control(Control value){}
    }
    private static volatile ClientSink clientSink;
    public static void setClientSink(ClientSink sink){clientSink=Objects.requireNonNull(sink);}
    public static void send(CustomPacketPayload payload){PacketDistributor.sendToServer(payload);}
    public static void register(RegisterPayloadHandlersEvent event){
        CabinetSyncNetwork.register(event);
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cabinet-room-10")
            .playToServer(Ready.TYPE,Ready.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)CabinetRooms.ready(s,p);}))
            .playToServer(Input.TYPE,Input.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)CabinetRooms.input(s,p);}))
            .playToServer(Reset.TYPE,Reset.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)CabinetRooms.reset(s,p);}))
            .playToServer(Media.TYPE,Media.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)CabinetRooms.media(s,p);}))
            .playToClient(Assignment.TYPE,Assignment.CODEC,(p,c)->dispatch(c,s->s.assignment(p)))
            .playToClient(NetplayStart.TYPE,NetplayStart.CODEC,(p,c)->dispatch(c,s->s.netplay(p)))
            .playToClient(PgmService.TYPE,PgmService.CODEC,(p,c)->dispatch(c,s->s.pgmService(p)))
            .playToClient(Control.TYPE,Control.CODEC,(p,c)->dispatch(c,s->s.control(p)))
            .playToClient(Seat.TYPE,Seat.CODEC,(p,c)->dispatch(c,s->s.seat(p)))
            .playToClient(Buttons.TYPE,Buttons.CODEC,(p,c)->dispatch(c,s->s.buttons(p)))
            .playToClient(Coin.TYPE,Coin.CODEC,(p,c)->dispatch(c,s->s.coin(p)))
            .playToClient(Stream.TYPE,Stream.CODEC,(p,c)->dispatch(c,s->s.media(p)))
            .playToClient(Moderator.TYPE,Moderator.CODEC,(p,c)->dispatch(c,s->s.moderator(p)));
    }
    private static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext context,java.util.function.Consumer<ClientSink> action){
        Connection source=context.connection();
        context.enqueueWork(()->{var sink=clientSink;if(source!=null&&sink!=null&&sink.acceptsConnection(source))action.accept(sink);});
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","cabinet_room_"+name);}
    private static void ids(UUID... ids){for(UUID id:ids)Objects.requireNonNull(id);}
    private static void checkedPort(int port){if(port<0||port>3)throw new IllegalArgumentException("Invalid cabinet port");}
    public record Assignment(UUID room,UUID member,UUID hostMember,int port,int capacity,CabinetTarget target,ResourceLocation backend,CabinetTarget primary,CabinetTarget secondary,CabinetSyncMode mode,boolean coinRequired) implements CustomPacketPayload {
        public Assignment(UUID room,UUID member,UUID hostMember,int port,int capacity,CabinetTarget target,ResourceLocation backend,CabinetTarget primary,CabinetTarget secondary,CabinetSyncMode mode){this(room,member,hostMember,port,capacity,target,backend,primary,secondary,mode,false);}
        public Assignment(UUID room,UUID member,UUID hostMember,int port,int capacity,CabinetTarget target,ResourceLocation backend,CabinetTarget primary,CabinetTarget secondary){this(room,member,hostMember,port,capacity,target,backend,primary,secondary,CabinetSyncMode.MEDIA);}
        public Assignment{ids(room,member,hostMember);checkedPort(port);Objects.requireNonNull(target);Objects.requireNonNull(backend);
            Objects.requireNonNull(mode);
            if(coinRequired&&!CabinetCoinPolicy.supported(backend.toString()))throw new IllegalArgumentException("Coin backend");
            Objects.requireNonNull(primary);
            if(!CabinetSeats.validCapacity(primary.dual(),secondary!=null,secondary!=null&&secondary.dual(),capacity)||port>=capacity||backend.toString().length()>128||mode!=CabinetSyncMode.SERVER_MEDIA&&(port==0)!=member.equals(hostMember)
                    ||!target.equals(port<CabinetSeats.end(primary.dual(),secondary!=null,true,capacity)?primary:secondary)
                    ||(secondary!=null&&(primary.identity().equals(secondary.identity())||primary.anchor().equals(secondary.anchor())||!primary.dimension().equals(secondary.dimension()))))
                throw new IllegalArgumentException("Invalid seat assignment");}
        public static final Type<Assignment> TYPE=new Type<>(id("assignment"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Assignment> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeUUID(p.hostMember);b.writeVarInt(p.port);b.writeVarInt(p.capacity);CabinetNetwork.writeTarget(b,p.target);b.writeUtf(p.backend.toString(),128);CabinetNetwork.writeTarget(b,p.primary);b.writeBoolean(p.secondary!=null);if(p.secondary!=null)CabinetNetwork.writeTarget(b,p.secondary);b.writeVarInt(p.mode.ordinal());b.writeBoolean(p.coinRequired);},b->new Assignment(b.readUUID(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarInt(),CabinetNetwork.readTarget(b),ResourceLocation.parse(b.readUtf(128)),CabinetNetwork.readTarget(b),b.readBoolean()?CabinetNetwork.readTarget(b):null,CabinetSyncMode.checked(b.readVarInt()),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record NetplayStart(Assignment assignment,long wire,UUID ticket) implements CustomPacketPayload {
        public NetplayStart{Objects.requireNonNull(assignment);Objects.requireNonNull(ticket);if(wire<(1L<<50)||assignment.mode()!=CabinetSyncMode.LOCAL_SYNC||assignment.capacity()>4)throw new IllegalArgumentException("Netplay assignment");}
        public static final Type<NetplayStart> TYPE=new Type<>(id("netplay_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf,NetplayStart> CODEC=StreamCodec.of((b,p)->{Assignment.CODEC.encode(b,p.assignment);b.writeLong(p.wire);b.writeUUID(p.ticket);},b->new NetplayStart(Assignment.CODEC.decode(b),b.readLong(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Control(UUID room,UUID member,boolean enabled) implements CustomPacketPayload {
        public Control{ids(room,member);}
        public static final Type<Control> TYPE=new Type<>(id("control"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Control> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeBoolean(p.enabled);},b->new Control(b.readUUID(),b.readUUID(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record PgmService(UUID room,UUID member) implements CustomPacketPayload {
        public PgmService{ids(room,member);}
        public static final Type<PgmService> TYPE=new Type<>(id("pgm_service"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PgmService> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);},b->new PgmService(b.readUUID(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Ready(UUID room,UUID member,boolean coinReleaseSupported) implements CustomPacketPayload {
        public Ready(UUID room,UUID member){this(room,member,false);}
        public Ready{ids(room,member);}
        public static final Type<Ready> TYPE=new Type<>(id("ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Ready> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeBoolean(p.coinReleaseSupported);},b->new Ready(b.readUUID(),b.readUUID(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server-only paid edge. Independent monotonically increasing IDs cannot collide with player input sequences. */
    public record Coin(UUID room,UUID hostMember,UUID member,int port,long sequence) implements CustomPacketPayload {
        public Coin{ids(room,hostMember,member);checkedPort(port);if(sequence<1)throw new IllegalArgumentException("Coin sequence");}
        public static final Type<Coin> TYPE=new Type<>(id("coin"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Coin> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.hostMember);b.writeUUID(p.member);b.writeVarInt(p.port);b.writeVarLong(p.sequence);},b->new Coin(b.readUUID(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Management authority can move between server-hosted seats without changing control ports or stream identity. */
    public record Moderator(UUID room,UUID member,boolean enabled) implements CustomPacketPayload {
        public Moderator{ids(room,member);}
        public static final Type<Moderator> TYPE=new Type<>(id("moderator"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Moderator> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeBoolean(p.enabled);},b->new Moderator(b.readUUID(),b.readUUID(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Input(UUID room,UUID member,long seq,int mask) implements CustomPacketPayload {
        public Input{ids(room,member);if(seq<0||mask<0||mask>4095)throw new IllegalArgumentException("Invalid input");}
        public static final Type<Input> TYPE=new Type<>(id("input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeVarLong(p.seq);b.writeVarInt(p.mask);},b->new Input(b.readUUID(),b.readUUID(),b.readVarLong(),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Explicit GUI/focus reset; ordinary release edges remain ordinary Input(mask=0). */
    public record Reset(UUID room,UUID member,long seq) implements CustomPacketPayload {
        public Reset{ids(room,member);if(seq<0)throw new IllegalArgumentException("Invalid reset sequence");}
        public static final Type<Reset> TYPE=new Type<>(id("reset"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reset> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.member);b.writeVarLong(p.seq);},b->new Reset(b.readUUID(),b.readUUID(),b.readVarLong()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Seat(UUID room,UUID hostMember,UUID member,int port,boolean joined) implements CustomPacketPayload {
        public Seat{ids(room,hostMember,member);checkedPort(port);}
        public static final Type<Seat> TYPE=new Type<>(id("seat"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Seat> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.hostMember);b.writeUUID(p.member);b.writeVarInt(p.port);b.writeBoolean(p.joined);},b->new Seat(b.readUUID(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Buttons(UUID room,UUID hostMember,UUID member,int port,long seq,int mask,boolean reset) implements CustomPacketPayload {
        public Buttons{ids(room,hostMember,member);checkedPort(port);if(seq<0||mask<0||mask>4095||(reset&&mask!=0))throw new IllegalArgumentException("Invalid forwarded input");}
        public static final Type<Buttons> TYPE=new Type<>(id("buttons"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Buttons> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.hostMember);b.writeUUID(p.member);b.writeVarInt(p.port);b.writeVarLong(p.seq);b.writeVarInt(p.mask);b.writeBoolean(p.reset);},b->new Buttons(b.readUUID(),b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarLong(),b.readVarInt(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Recipient lease prevents a delayed old stream from entering a new membership in the same room. */
    public record Stream(UUID member,Media media) implements CustomPacketPayload {
        public Stream{Objects.requireNonNull(member);Objects.requireNonNull(media);}
        public static final Type<Stream> TYPE=new Type<>(id("stream"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Stream> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.member);Media.CODEC.encode(b,p.media);},b->new Stream(b.readUUID(),Media.CODEC.decode(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Media(UUID room,UUID hostMember,long sequence,int kind,int index,int count,int width,int height,float aspect,int rotation,int rawLength,byte[] data) implements CustomPacketPayload {
        public Media{ids(room,hostMember);CabinetRoomMedia.check(sequence,kind,index,count,width,height,aspect,rotation,rawLength,data);data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        public static final Type<Media> TYPE=new Type<>(id("media"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Media> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.room);b.writeUUID(p.hostMember);b.writeVarLong(p.sequence);b.writeVarInt(p.kind);b.writeVarInt(p.index);b.writeVarInt(p.count);b.writeVarInt(p.width);b.writeVarInt(p.height);b.writeFloat(p.aspect);b.writeVarInt(p.rotation);b.writeVarInt(p.rawLength);b.writeByteArray(p.data);},b->new Media(b.readUUID(),b.readUUID(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readFloat(),b.readVarInt(),b.readVarInt(),b.readByteArray(CabinetRoomMedia.CHUNK)));
        CabinetRoomMedia.Part part(){return new CabinetRoomMedia.Part(sequence,kind,index,count,width,height,aspect,rotation,rawLength,data);}
        static Media from(UUID room,UUID host,CabinetRoomMedia.Part p){return new Media(room,host,p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data());}
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
