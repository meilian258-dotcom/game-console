// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.cabinet.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** MD authorization only. Shared content cards and Watch carry ROM and bounded media. */
public final class MdPublicNetwork {
    private MdPublicNetwork(){}
    public interface Client {
        boolean current(Connection connection);
        void start(Start value); void seat(Seat value); void end(End value);
        void input(Input value); void media(Media value);
        void preference(Preference value);
        void privateStart(PrivateStart value);
        default void privateActivate(PrivateActivated value){}
        default void activate(Activated value){}
        default void visual(Visual value){}
        default void downloadDenied(DownloadDenied value){}
    }
    private static volatile Client client;
    public static void client(Client value){client=Objects.requireNonNull(value);}
    private static ResourceLocation id(String value){return ResourceLocation.fromNamespaceAndPath(MdMod.ID,"public_"+value);}
    public record Start(long wire,UUID ticket,UUID content,WatchNetwork.Start display,boolean save,boolean resume,String profile,String rom,String saveContent,boolean netplay) implements CustomPacketPayload {
        public Start {if(wire<=0||!digest(profile)||!digest(rom)||!digest(saveContent))throw new IllegalArgumentException("MD start");}
        public Start(long wire,UUID ticket,UUID content,WatchNetwork.Start display,boolean save,boolean resume,String profile,String rom){this(wire,ticket,content,display,save,resume,profile,rom,rom,false);}
        public static final Type<Start> TYPE=new Type<>(id("start"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Start> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeUUID(p.ticket);b.writeUUID(p.content);WatchNetwork.Start.CODEC.encode(b,p.display);b.writeBoolean(p.save);b.writeBoolean(p.resume);b.writeUtf(p.profile,64);b.writeUtf(p.rom,64);b.writeUtf(p.saveContent,64);b.writeBoolean(p.netplay);},b->new Start(b.readVarLong(),b.readUUID(),b.readUUID(),WatchNetwork.Start.CODEC.decode(b),b.readBoolean(),b.readBoolean(),b.readUtf(64),b.readUtf(64),b.readUtf(64),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Seat(long wire,WatchNetwork.Start display,int port,UUID loan,UUID ticket,String rom,int bytes,boolean netplay) implements CustomPacketPayload {
        public Seat {if(wire<=0||port<0||port>1||netplay&&(!digest(rom)||bytes<512||bytes>cn.piq.mdhome.client.MdRom.MAX))throw new IllegalArgumentException("MD seat");Objects.requireNonNull(ticket);}
        public Seat(long wire,WatchNetwork.Start display,int port,UUID loan){this(wire,display,port,loan,new UUID(0,0),"",0,false);}
        public static final Type<Seat> TYPE=new Type<>(id("seat"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Seat> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);WatchNetwork.Start.CODEC.encode(b,p.display);b.writeByte(p.port);b.writeUUID(p.loan);b.writeUUID(p.ticket);b.writeUtf(p.rom,64);b.writeVarInt(p.bytes);b.writeBoolean(p.netplay);},b->new Seat(b.readVarLong(),WatchNetwork.Start.CODEC.decode(b),b.readUnsignedByte(),b.readUUID(),b.readUUID(),b.readUtf(64),b.readVarInt(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record End(long wire,UUID loan,boolean shutdown,String reason) implements CustomPacketPayload {
        public End {if(wire<=0||reason.length()>160)throw new IllegalArgumentException("MD end");}
        public static final Type<End> TYPE=new Type<>(id("end"));
        public static final StreamCodec<RegistryFriendlyByteBuf,End> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeUUID(p.loan);b.writeBoolean(p.shutdown);b.writeUtf(p.reason,160);},b->new End(b.readVarLong(),b.readUUID(),b.readBoolean(),b.readUtf(160)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Input(long wire,int port,UUID loan,long sequence,int mask) implements CustomPacketPayload {
        public Input {if(wire<=0||port<0||port>1||sequence<0||(mask&~4095)!=0)throw new IllegalArgumentException("MD input");}
        public static final Type<Input> TYPE=new Type<>(id("input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeByte(p.port);b.writeUUID(p.loan);b.writeVarLong(p.sequence);b.writeVarInt(p.mask);},b->new Input(b.readVarLong(),b.readUnsignedByte(),b.readUUID(),b.readVarLong(),b.readVarInt()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Bounded nearby public-controller presentation, not input or seat authority. */
    public record Visual(ResourceLocation dimension,net.minecraft.core.BlockPos console,UUID hardware,long wire,
                         UUID player,UUID loan,int port,long sequence,int mask,int pressedMask,boolean reset) implements CustomPacketPayload {
        public Visual {
            Objects.requireNonNull(dimension);console=Objects.requireNonNull(console).immutable();Objects.requireNonNull(hardware);
            Objects.requireNonNull(player);Objects.requireNonNull(loan);
            if(dimension.toString().length()>128||wire<=0||port<0||port>1||sequence<=0||(mask&~4095)!=0||(pressedMask&~4095)!=0)throw new IllegalArgumentException("MD visual");
            if(reset&&(mask!=0||pressedMask!=0))throw new IllegalArgumentException("MD visual reset");
        }
        public static final Type<Visual> TYPE=new Type<>(id("visual"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Visual> CODEC=StreamCodec.of((b,p)->{
            b.writeUtf(p.dimension.toString(),128);b.writeBlockPos(p.console);b.writeUUID(p.hardware);b.writeVarLong(p.wire);
            b.writeUUID(p.player);b.writeUUID(p.loan);b.writeByte(p.port);b.writeVarLong(p.sequence);b.writeVarInt(p.mask);b.writeVarInt(p.pressedMask);b.writeBoolean(p.reset);
        },b->new Visual(ResourceLocation.parse(b.readUtf(128)),b.readBlockPos(),b.readUUID(),b.readVarLong(),b.readUUID(),b.readUUID(),b.readUnsignedByte(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Media(long wire,UUID loan,CabinetRoomNetwork.Media media) implements CustomPacketPayload {
        public Media {if(wire<=0)throw new IllegalArgumentException("MD media");Objects.requireNonNull(loan);Objects.requireNonNull(media);}
        public static final Type<Media> TYPE=new Type<>(id("media"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Media> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeUUID(p.loan);CabinetRoomNetwork.Media.CODEC.encode(b,p.media);},b->new Media(b.readVarLong(),b.readUUID(),CabinetRoomNetwork.Media.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
        public CabinetMediaPacket packet(){var p=media;return new CabinetMediaPacket(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),p.data());}
    }
    public static boolean belongsTo(Media packet,long wire,UUID loan,WatchDescriptor source){return packet.wire()==wire&&packet.loan().equals(loan)&&source.source().equals(packet.media().room())&&source.hostLease().equals(packet.media().hostMember());}
    public record Preference(boolean privatePlay) implements CustomPacketPayload {
        public static final Type<Preference> TYPE=new Type<>(id("preference"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Preference> CODEC=StreamCodec.of((b,p)->b.writeBoolean(p.privatePlay),b->new Preference(b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record PrivateStart(UUID content,boolean start) implements CustomPacketPayload {
        public PrivateStart {Objects.requireNonNull(content);}
        public static final Type<PrivateStart> TYPE=new Type<>(id("private_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PrivateStart> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.content);b.writeBoolean(p.start);},b->new PrivateStart(b.readUUID(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Activated(long wire) implements CustomPacketPayload {
        public Activated{if(wire<=0)throw new IllegalArgumentException("MD activation");}
        public static final Type<Activated> TYPE=new Type<>(id("activated"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Activated> CODEC=StreamCodec.of((b,p)->b.writeVarLong(p.wire),b->new Activated(b.readVarLong()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record PrivateActivated(UUID content) implements CustomPacketPayload {
        public PrivateActivated{Objects.requireNonNull(content);}
        public static final Type<PrivateActivated> TYPE=new Type<>(id("private_activated"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PrivateActivated> CODEC=StreamCodec.of((b,p)->b.writeUUID(p.content),b->new PrivateActivated(b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record PrivateFinished(UUID content,boolean closed,boolean saved) implements CustomPacketPayload {
        public PrivateFinished{Objects.requireNonNull(content);}
        public static final Type<PrivateFinished> TYPE=new Type<>(id("private_finished"));
        public static final StreamCodec<RegistryFriendlyByteBuf,PrivateFinished> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.content);b.writeBoolean(p.closed);b.writeBoolean(p.saved);},b->new PrivateFinished(b.readUUID(),b.readBoolean(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Local native teardown only; never accepted as proof of server persistence. */
    public record Closed(long wire,boolean closed) implements CustomPacketPayload {
        public Closed{if(wire<=0)throw new IllegalArgumentException("MD close");}
        public static final Type<Closed> TYPE=new Type<>(id("closed"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Closed> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeBoolean(p.closed);},b->new Closed(b.readVarLong(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Release(long wire,int port,UUID loan) implements CustomPacketPayload {
        public Release {if(wire<=0||port<0||port>1)throw new IllegalArgumentException("MD release");Objects.requireNonNull(loan);}
        public static final Type<Release> TYPE=new Type<>(id("release"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Release> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeByte(p.port);b.writeUUID(p.loan);},b->new Release(b.readVarLong(),b.readUnsignedByte(),b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Correlation nonce only: server independently revalidates the seat/watch authorization. */
    public record Download(long wire,UUID request,String rom) implements CustomPacketPayload {
        public Download{if(wire<=0||!digest(rom))throw new IllegalArgumentException("MD download");Objects.requireNonNull(request);}
        public static final Type<Download> TYPE=new Type<>(id("download"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Download> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeUUID(p.request);b.writeUtf(p.rom,64);},b->new Download(b.readVarLong(),b.readUUID(),b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record DownloadDenied(UUID request) implements CustomPacketPayload {
        public DownloadDenied{Objects.requireNonNull(request);}
        public static final Type<DownloadDenied> TYPE=new Type<>(id("download_denied"));
        public static final StreamCodec<RegistryFriendlyByteBuf,DownloadDenied> CODEC=StreamCodec.of((b,p)->b.writeUUID(p.request),b->new DownloadDenied(b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** A remote replica has restored its seed; grants only its already leased physical input port. */
    public record SeatReady(long wire,int port,UUID loan) implements CustomPacketPayload {
        public SeatReady{if(wire<=0||port<0||port>1)throw new IllegalArgumentException("MD seat ready");Objects.requireNonNull(loan);}
        public static final Type<SeatReady> TYPE=new Type<>(id("seat_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf,SeatReady> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.wire);b.writeByte(p.port);b.writeUUID(p.loan);},b->new SeatReady(b.readVarLong(),b.readUnsignedByte(),b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"md-public-4")
            .playToClient(Start.TYPE,Start.CODEC,(p,c)->dispatch(c,h->h.start(p)))
            .playToClient(PrivateStart.TYPE,PrivateStart.CODEC,(p,c)->dispatch(c,h->h.privateStart(p)))
            .playToClient(Activated.TYPE,Activated.CODEC,(p,c)->dispatch(c,h->h.activate(p)))
            .playToClient(PrivateActivated.TYPE,PrivateActivated.CODEC,(p,c)->dispatch(c,h->h.privateActivate(p)))
            .playToClient(Seat.TYPE,Seat.CODEC,(p,c)->dispatch(c,h->h.seat(p)))
            .playToClient(End.TYPE,End.CODEC,(p,c)->dispatch(c,h->h.end(p)))
            .playToClient(Media.TYPE,Media.CODEC,(p,c)->dispatch(c,h->h.media(p)))
            .playToClient(Visual.TYPE,Visual.CODEC,(p,c)->dispatch(c,h->h.visual(p)))
            .playToClient(DownloadDenied.TYPE,DownloadDenied.CODEC,(p,c)->dispatch(c,h->h.downloadDenied(p)))
            .playToServer(Release.TYPE,Release.CODEC,(p,c)->server(c,h->MdPublicServer.release(h,p)))
            .playToServer(Download.TYPE,Download.CODEC,(p,c)->server(c,h->MdPublicServer.download(h,p)))
            .playToServer(SeatReady.TYPE,SeatReady.CODEC,(p,c)->server(c,h->MdPublicServer.seatReady(h,p)))
            .playToServer(Closed.TYPE,Closed.CODEC,(p,c)->server(c,h->MdPublicServer.closed(h,p)))
            .playToServer(PrivateFinished.TYPE,PrivateFinished.CODEC,(p,c)->server(c,h->MdPrivateServer.finished(h,p)))
            .playBidirectional(Input.TYPE,Input.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer)server(c,h->MdPublicServer.input(h,p));else dispatch(c,h->h.input(p));})
            .playBidirectional(Preference.TYPE,Preference.CODEC,(p,c)->{if(c.player() instanceof ServerPlayer)server(c,h->MdPublicServer.preference(h,p.privatePlay()));else dispatch(c,h->h.preference(p));});
    }
    private static void dispatch(IPayloadContext c,Consumer<Client> call){var connection=c.connection();c.enqueueWork(()->{var sink=client;if(sink!=null&&sink.current(connection))call.accept(sink);});}
    private static void server(IPayloadContext c,Consumer<ServerPlayer> call){var connection=c.connection();c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p&&p.connection.getConnection()==connection&&MdPublicServer.current(p))call.accept(p);});}
    public static void send(ServerPlayer p,CustomPacketPayload value){net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,value);}
    public static void send(CustomPacketPayload value){net.neoforged.neoforge.network.PacketDistributor.sendToServer(value);}
    public static boolean media(Connection connection,long wire,UUID loan,List<CabinetMediaPacket> batch){
        var result=new ArrayList<Media>(batch.size());int[] sizes=new int[batch.size()];
        for(int i=0;i<batch.size();i++){var p=batch.get(i);byte[] data=p.data();result.add(new Media(wire,loan,new CabinetRoomNetwork.Media(p.room(),p.hostMember(),p.sequence(),p.kind(),p.index(),p.count(),p.width(),p.height(),p.aspect(),p.rotation(),p.rawLength(),data)));sizes[i]=data.length+384;}
        return CabinetMediaSender.sendPayloads(connection,result,sizes,false);
    }
}
