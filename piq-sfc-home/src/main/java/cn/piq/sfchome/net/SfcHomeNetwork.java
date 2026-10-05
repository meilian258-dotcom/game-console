package cn.piq.sfchome.net;

import cn.piq.sfchome.server.SfcHomeServer;
import cn.piq.sfchome.server.SfcCartridgeEditorService;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Independent twelve-button protocol. Never serializes NES's eight-bit input payloads. */
public final class SfcHomeNetwork {
    public static final String CORE_BUILD = cn.piq.sfchome.core.LibretroSfcCore.BUILD+":jni-v1";
    public static final int MAX_ROM = 32 * 1024 * 1024 + 512, CHUNK = 65536;
    public static final int REFRESH=0, WRITE=1, UPLOAD_START=2, UPLOAD_CHUNK=3, UPLOAD_FINISH=4, CANCEL=5,
            SAVE_NAME=6, COVER_START=7, CLEAR_COVER=8, RESTORE_COVER=9, SET_PLAYERS=10, USE_COVER=11, SET_SAVE_MODE=12;
    public static final int MAX_COVER=2*1024*1024;
    private SfcHomeNetwork() {}
    public interface ClientHandler {
        void session(Session value); void frames(Frames value); void stopped(Stopped value);
        void romChunk(RomChunk value); void editor(Editor value);
        default void control(Control value){}
        default void netplay(NetplayStart value){}
        default void netplayActivated(NetplayActivated value){}
        default void hosted(SfcHostedNetwork.Stream value){}
        default void hostedReset(SfcHostedNetwork.Reset value){}
        default boolean acceptsConnection(Object source){return false;}
    }
    private static volatile ClientHandler client;
    private static volatile java.util.function.Consumer<CoverChunk> coverClient;
    public static void setCoverHandler(java.util.function.Consumer<CoverChunk> handler){coverClient=Objects.requireNonNull(handler);}
    public static void setClientHandler(ClientHandler handler) { client = Objects.requireNonNull(handler); }
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"13");
        SfcHostedNetwork.register(event);
        SfcJoinNetwork.register(event);
        r.playToServer(CoverRequest.TYPE,CoverRequest.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)cn.piq.sfchome.server.SfcCoverService.download(s,p);}));
        r.playToClient(CoverChunk.TYPE,CoverChunk.CODEC,(p,c)->dispatch(c,h->{var covers=coverClient;if(covers!=null)covers.accept(p);}));
        r.playToServer(Input.TYPE,Input.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s) SfcHomeServer.input(s,p);}));
        r.playToServer(RomRequest.TYPE,RomRequest.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s) SfcCartridgeEditorService.download(s,p.romSha());}));
        r.playToServer(EditorAction.TYPE,EditorAction.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s) SfcCartridgeEditorService.handle(s,p);}));
        r.playToClient(Session.TYPE,Session.CODEC,(p,c)->dispatch(c,h->h.session(p)));
        r.playToClient(NetplayStart.TYPE,NetplayStart.CODEC,(p,c)->dispatch(c,h->h.netplay(p)));
        r.playToClient(NetplayActivated.TYPE,NetplayActivated.CODEC,(p,c)->dispatch(c,h->h.netplayActivated(p)));
        r.playToClient(Control.TYPE,Control.CODEC,(p,c)->dispatch(c,h->h.control(p)));
        r.playToClient(Frames.TYPE,Frames.CODEC,(p,c)->dispatch(c,h->h.frames(p)));
        r.playToClient(Stopped.TYPE,Stopped.CODEC,(p,c)->dispatch(c,h->h.stopped(p)));
        r.playToClient(RomChunk.TYPE,RomChunk.CODEC,(p,c)->dispatch(c,h->h.romChunk(p)));
        r.playToClient(Editor.TYPE,Editor.CODEC,(p,c)->dispatch(c,h->h.editor(p)));
    }
    static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext context,java.util.function.Consumer<ClientHandler> action){
        Object source=context.connection();
        context.enqueueWork(()->{var h=client;if(h!=null&&h.acceptsConnection(source))action.accept(h);});
    }
    public static void sendReady(UUID lease,Ready value){PacketDistributor.sendToServer(new SfcJoinNetwork.ControllerReady(lease,value));}
    public static void sendInput(Input value){PacketDistributor.sendToServer(value);}
    public static void leave(UUID lease,Leave value){PacketDistributor.sendToServer(new SfcJoinNetwork.ControllerLeave(lease,value));}
    public static void requestRom(String hash){PacketDistributor.sendToServer(new RomRequest(hash));}
    public static void edit(EditorAction value){PacketDistributor.sendToServer(value);}
    public static void send(ServerPlayer player,CustomPacketPayload value){PacketDistributor.sendToPlayer(player,value);}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home",path));}
    public static String hash(String value,boolean empty){if(value==null||!(empty&&value.isEmpty())&&!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid SHA-256");return value;}
    private static String text(String value,int max){if(value==null||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid text");return value;}
    private static void sessionId(long id,int epoch){if(id<=0||epoch<=0)throw new IllegalArgumentException("Invalid session");}
    /** controllerLease is the runtime capability: a host token for executionHost, a controller token otherwise. */
    public record Session(long sessionId,int epoch,ResourceLocation dimension,BlockPos consolePos,UUID consoleId,BlockPos tvPos,UUID tvId,UUID linkId,String romSha,String coreBuild,int port,UUID controllerLease,boolean executionHost,int syncMode,UUID mediaSource,UUID mediaStream) implements CustomPacketPayload {
        public Session{SfcHomeNetwork.sessionId(sessionId,epoch);Objects.requireNonNull(dimension);consolePos=Objects.requireNonNull(consolePos).immutable();tvPos=Objects.requireNonNull(tvPos).immutable();Objects.requireNonNull(consoleId);Objects.requireNonNull(tvId);Objects.requireNonNull(linkId);Objects.requireNonNull(controllerLease);Objects.requireNonNull(mediaSource);Objects.requireNonNull(mediaStream);hash(romSha,false);text(coreBuild,96);SfcPlaybackMode.checked(syncMode);if(port< -1||port>1||port==-1&&!executionHost)throw new IllegalArgumentException("Invalid port or mode");}
        public Session(long sessionId,int epoch,ResourceLocation dimension,BlockPos consolePos,UUID consoleId,BlockPos tvPos,UUID tvId,UUID linkId,String romSha,String coreBuild,int port,UUID controllerLease,boolean executionHost){this(sessionId,epoch,dimension,consolePos,consoleId,tvPos,tvId,linkId,romSha,coreBuild,port,controllerLease,executionHost,1,consoleId,controllerLease);}
        public boolean serverHosted(){return syncMode==2;}
        public boolean playerHosted(){return syncMode==SfcPlaybackMode.PLAYER_MEDIA;}
        public boolean receivesMedia(){return SfcPlaybackMode.receivesMedia(syncMode,executionHost);}
        public boolean checksState(){return SfcPlaybackMode.checksState(syncMode);}
        public Session(long sessionId,int epoch,ResourceLocation dimension,BlockPos consolePos,UUID consoleId,BlockPos tvPos,UUID tvId,UUID linkId,String romSha,String coreBuild,int port,UUID controllerLease){this(sessionId,epoch,dimension,consolePos,consoleId,tvPos,tvId,linkId,romSha,coreBuild,port,controllerLease,port==0);}
        public static final Type<Session> TYPE=id("session");
        public static final StreamCodec<RegistryFriendlyByteBuf,Session> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeResourceLocation(v.dimension);b.writeBlockPos(v.consolePos);b.writeUUID(v.consoleId);b.writeBlockPos(v.tvPos);b.writeUUID(v.tvId);b.writeUUID(v.linkId);b.writeUtf(v.romSha,64);b.writeUtf(v.coreBuild,96);b.writeByte(v.port);b.writeUUID(v.controllerLease);b.writeBoolean(v.executionHost);b.writeByte(v.syncMode);b.writeUUID(v.mediaSource);b.writeUUID(v.mediaStream);},b->new Session(b.readVarLong(),b.readVarInt(),b.readResourceLocation(),b.readBlockPos(),b.readUUID(),b.readBlockPos(),b.readUUID(),b.readUUID(),b.readUtf(64),b.readUtf(96),b.readByte(),b.readUUID(),b.readBoolean(),b.readUnsignedByte(),b.readUUID(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record NetplayStart(Session session,long wire,UUID ticket) implements CustomPacketPayload {
        public NetplayStart{Objects.requireNonNull(session);Objects.requireNonNull(ticket);if(session.syncMode()!=3||wire<(1L<<50))throw new IllegalArgumentException("Netplay grant");}
        public static final Type<NetplayStart> TYPE=id("netplay_start");
        public static final StreamCodec<RegistryFriendlyByteBuf,NetplayStart> CODEC=StreamCodec.of((b,v)->{Session.CODEC.encode(b,v.session);b.writeLong(v.wire);b.writeUUID(v.ticket);},b->new NetplayStart(Session.CODEC.decode(b),b.readLong(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Sent only after the server commits launch and (when enabled) save authority. */
    public record NetplayActivated(long sessionId,int epoch,long wire,UUID ticket) implements CustomPacketPayload {
        public NetplayActivated{SfcHomeNetwork.sessionId(sessionId,epoch);Objects.requireNonNull(ticket);if(wire<(1L<<50))throw new IllegalArgumentException("Netplay activation");}
        public static final Type<NetplayActivated> TYPE=id("netplay_activated");
        public static final StreamCodec<RegistryFriendlyByteBuf,NetplayActivated> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeLong(v.wire);b.writeUUID(v.ticket);},b->new NetplayActivated(b.readVarLong(),b.readVarInt(),b.readLong(),b.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Control(long sessionId,int epoch,UUID lease,int port,boolean active) implements CustomPacketPayload {
        public Control{SfcHomeNetwork.sessionId(sessionId,epoch);Objects.requireNonNull(lease);if(port<0||port>1)throw new IllegalArgumentException("Invalid control port");}
        public static final Type<Control> TYPE=id("control");
        public static final StreamCodec<RegistryFriendlyByteBuf,Control> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeUUID(v.lease);b.writeByte(v.port);b.writeBoolean(v.active);},b->new Control(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readUnsignedByte(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Ready(long sessionId,int epoch,String romSha,String coreBuild,double targetFps,String initialStateHash) implements CustomPacketPayload {
        public Ready{SfcHomeNetwork.sessionId(sessionId,epoch);hash(romSha,false);text(coreBuild,96);hash(initialStateHash,false);if(!Double.isFinite(targetFps)||targetFps<49||targetFps>61)throw new IllegalArgumentException("Invalid frame rate");}
        public static final Type<Ready> TYPE=id("ready");
        public static final StreamCodec<RegistryFriendlyByteBuf,Ready> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeUtf(v.romSha,64);b.writeUtf(v.coreBuild,96);b.writeDouble(v.targetFps);b.writeUtf(v.initialStateHash,64);},b->new Ready(b.readVarLong(),b.readVarInt(),b.readUtf(64),b.readUtf(96),b.readDouble(),b.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Input(long sessionId,int epoch,int sequence,int buttonMask,boolean forceRelease) implements CustomPacketPayload {
        public Input{SfcHomeNetwork.sessionId(sessionId,epoch);if(sequence<0||buttonMask<0||buttonMask>4095||forceRelease&&buttonMask!=0)throw new IllegalArgumentException("Invalid input");}
        public static final Type<Input> TYPE=id("input");
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeVarInt(v.sequence);b.writeVarInt(v.buttonMask);b.writeBoolean(v.forceRelease);},b->new Input(b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readBoolean()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Leave(long sessionId,int epoch) implements CustomPacketPayload {
        public Leave{SfcHomeNetwork.sessionId(sessionId,epoch);}public static final Type<Leave> TYPE=id("leave");
        public static final StreamCodec<RegistryFriendlyByteBuf,Leave> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);},b->new Leave(b.readVarLong(),b.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Frames(long sessionId,int epoch,int firstFrame,int[] p1Masks,int[] p2Masks) implements CustomPacketPayload {
        public Frames{SfcHomeNetwork.sessionId(sessionId,epoch);if(firstFrame<0||p1Masks==null||p2Masks==null||p1Masks.length<1||p1Masks.length>4||p1Masks.length!=p2Masks.length)throw new IllegalArgumentException("Invalid frames");p1Masks=p1Masks.clone();p2Masks=p2Masks.clone();for(int m:p1Masks)if(m<0||m>4095)throw new IllegalArgumentException("Invalid mask");for(int m:p2Masks)if(m<0||m>4095)throw new IllegalArgumentException("Invalid mask");}
        @Override public int[] p1Masks(){return p1Masks.clone();}@Override public int[] p2Masks(){return p2Masks.clone();}
        public static final Type<Frames> TYPE=id("frames");
        public static final StreamCodec<RegistryFriendlyByteBuf,Frames> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeVarInt(v.firstFrame);b.writeByte(v.p1Masks.length);for(int i=0;i<v.p1Masks.length;i++){b.writeVarInt(v.p1Masks[i]);b.writeVarInt(v.p2Masks[i]);}},b->{long s=b.readVarLong();int e=b.readVarInt(),f=b.readVarInt(),n=b.readUnsignedByte();if(n<1||n>4)throw new IllegalArgumentException("Too many frames");int[]a=new int[n],d=new int[n];for(int i=0;i<n;i++){a[i]=b.readVarInt();d[i]=b.readVarInt();}return new Frames(s,e,f,a,d);});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Stopped(long sessionId,int epoch,String reason) implements CustomPacketPayload {
        public Stopped{SfcHomeNetwork.sessionId(sessionId,epoch);text(reason,192);}
        public static final Type<Stopped> TYPE=id("stopped");
        public static final StreamCodec<RegistryFriendlyByteBuf,Stopped> CODEC=StreamCodec.of((b,v)->{b.writeVarLong(v.sessionId);b.writeVarInt(v.epoch);b.writeUtf(v.reason,192);},b->new Stopped(b.readVarLong(),b.readVarInt(),b.readUtf(192)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record RomRequest(String romSha) implements CustomPacketPayload {
        public RomRequest{hash(romSha,false);}public static final Type<RomRequest> TYPE=id("rom_request");
        public static final StreamCodec<RegistryFriendlyByteBuf,RomRequest> CODEC=StreamCodec.of((b,v)->b.writeUtf(v.romSha,64),b->new RomRequest(b.readUtf(64)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record RomChunk(String romSha,int total,int offset,byte[] data) implements CustomPacketPayload {
        public RomChunk{hash(romSha,false);if(total<32768||total>MAX_ROM||offset<0||data==null||data.length<1||data.length>CHUNK||(long)offset+data.length>total)throw new IllegalArgumentException("Invalid ROM chunk");data=data.clone();}
        @Override public byte[] data(){return data.clone();}public static final Type<RomChunk> TYPE=id("rom_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf,RomChunk> CODEC=StreamCodec.of((b,v)->{b.writeUtf(v.romSha,64);b.writeVarInt(v.total);b.writeVarInt(v.offset);b.writeByteArray(v.data);},b->new RomChunk(b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record RomEntry(String sha256,String fileName,int size){public RomEntry{hash(sha256,false);text(fileName,128);if(size<32768||size>MAX_ROM)throw new IllegalArgumentException("Invalid ROM size");}}
    public record Editor(UUID token,boolean open,String message,String currentRom,String title,String coverSha,int maxPlayers,boolean explicitPlayers,int capabilities,List<RomEntry> catalog,List<String> covers,int saveMode) implements CustomPacketPayload {
        public Editor(UUID token,boolean open,String message,String currentRom,String title,String coverSha,int maxPlayers,boolean explicitPlayers,int capabilities,List<RomEntry> catalog,List<String> covers){this(token,open,message,currentRom,title,coverSha,maxPlayers,explicitPlayers,capabilities,catalog,covers,2);}
        public Editor{Objects.requireNonNull(token);text(message,256);hash(currentRom,true);text(title,128);hash(coverSha,true);if(saveMode<0||saveMode>2)throw new IllegalArgumentException("Invalid save mode");if(maxPlayers!=1&&maxPlayers!=2)throw new IllegalArgumentException("Invalid players");SfcEditorPermissions.checked(capabilities);if(catalog==null||catalog.size()>256||covers==null||covers.size()>256)throw new IllegalArgumentException("Invalid catalog");catalog=List.copyOf(catalog);covers=List.copyOf(covers);for(String cover:covers)hash(cover,false);if(covers.stream().distinct().count()!=covers.size())throw new IllegalArgumentException("Duplicate cover");}
        public Editor(UUID token,boolean open,String message,String currentRom,String title,String coverSha,int maxPlayers,boolean explicitPlayers,int capabilities,List<RomEntry> catalog){this(token,open,message,currentRom,title,coverSha,maxPlayers,explicitPlayers,capabilities,catalog,List.of());}
        public Editor(UUID token,boolean open,String message,String currentRom,String title,String coverSha,int maxPlayers,boolean explicitPlayers,List<RomEntry> catalog){this(token,open,message,currentRom,title,coverSha,maxPlayers,explicitPlayers,0,catalog);}
        public Editor(UUID token,boolean open,String message,String currentRom,String title,List<RomEntry> catalog){this(token,open,message,currentRom,title,"",2,false,0,catalog);}
        public static final Type<Editor> TYPE=id("editor");
        public static final StreamCodec<RegistryFriendlyByteBuf,Editor> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.token);b.writeBoolean(v.open);b.writeUtf(v.message,256);b.writeUtf(v.currentRom,64);b.writeUtf(v.title,128);b.writeUtf(v.coverSha,64);b.writeByte(v.maxPlayers);b.writeBoolean(v.explicitPlayers);b.writeByte(v.capabilities);b.writeVarInt(v.catalog.size());for(var x:v.catalog){b.writeUtf(x.sha256,64);b.writeUtf(x.fileName,128);b.writeVarInt(x.size);}b.writeVarInt(v.covers.size());for(String cover:v.covers)b.writeUtf(cover,64);b.writeByte(v.saveMode);},b->{UUID t=b.readUUID();boolean o=b.readBoolean();String m=b.readUtf(256),r=b.readUtf(64),n=b.readUtf(128),h=b.readUtf(64);int players=b.readUnsignedByte();boolean explicit=b.readBoolean();int capabilities=SfcEditorPermissions.checked(b.readUnsignedByte());int c=b.readVarInt();if(c<0||c>256)throw new IllegalArgumentException("Invalid catalog");var l=new ArrayList<RomEntry>(c);for(int i=0;i<c;i++)l.add(new RomEntry(b.readUtf(64),b.readUtf(128),b.readVarInt()));int ncover=b.readVarInt();if(ncover<0||ncover>256)throw new IllegalArgumentException("Invalid covers");var covers=new ArrayList<String>(ncover);for(int i=0;i<ncover;i++)covers.add(b.readUtf(64));return new Editor(t,o,m,r,n,h,players,explicit,capabilities,l,covers,b.readUnsignedByte());});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record EditorAction(UUID token,int operation,String hash,String name,int total,int offset,byte[] data) implements CustomPacketPayload {
        public EditorAction{Objects.requireNonNull(token);SfcHomeNetwork.hash(hash,true);text(name,128);if(operation<0||operation>SET_SAVE_MODE||total<0||total>MAX_ROM||offset<0||offset>MAX_ROM||data==null||data.length>CHUNK)throw new IllegalArgumentException("Invalid editor action");data=data.clone();}
        @Override public byte[] data(){return data.clone();}public static final Type<EditorAction> TYPE=id("editor_action");
        public static final StreamCodec<RegistryFriendlyByteBuf,EditorAction> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.token);b.writeByte(v.operation);b.writeUtf(v.hash,64);b.writeUtf(v.name,128);b.writeVarInt(v.total);b.writeVarInt(v.offset);b.writeByteArray(v.data);},b->new EditorAction(b.readUUID(),b.readUnsignedByte(),b.readUtf(64),b.readUtf(128),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record CoverRequest(String coverSha,BlockPos consolePos) implements CustomPacketPayload {
        public CoverRequest{hash(coverSha,false);consolePos=Objects.requireNonNull(consolePos).immutable();}
        public static final Type<CoverRequest> TYPE=id("cover_request");
        public static final StreamCodec<RegistryFriendlyByteBuf,CoverRequest> CODEC=StreamCodec.of((b,v)->{b.writeUtf(v.coverSha,64);b.writeBlockPos(v.consolePos);},b->new CoverRequest(b.readUtf(64),b.readBlockPos()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record CoverChunk(String coverSha,int total,int offset,byte[] data) implements CustomPacketPayload {
        public CoverChunk{hash(coverSha,false);if(total<33||total>MAX_COVER||offset<0||data==null||data.length<1||data.length>CHUNK||(long)offset+data.length>total)throw new IllegalArgumentException("Invalid cover chunk");data=data.clone();}
        @Override public byte[] data(){return data.clone();}
        public static final Type<CoverChunk> TYPE=id("cover_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf,CoverChunk> CODEC=StreamCodec.of((b,v)->{b.writeUtf(v.coverSha,64);b.writeVarInt(v.total);b.writeVarInt(v.offset);b.writeByteArray(v.data);},b->new CoverChunk(b.readUtf(64),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
