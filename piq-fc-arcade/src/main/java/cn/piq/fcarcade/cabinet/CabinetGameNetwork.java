package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** All paths are server-assigned; chunk requests are scoped to a live cabinet lease. */
public final class CabinetGameNetwork {
    public static final int OPEN=0,PUT=1,GET=2,END=3,CANCEL=4;
    public interface Sink {void reply(Connection connection,Reply reply);}
    private static volatile Sink sink;
    private static volatile java.util.function.BiConsumer<Connection,LibraryReply> librarySink;
    public static void setSink(Sink value){sink=Objects.requireNonNull(value);}
    public static void setLibrarySink(java.util.function.BiConsumer<Connection,LibraryReply> value){librarySink=Objects.requireNonNull(value);}
    public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cabinet-game-7")
            .playToServer(LibraryRequest.TYPE,LibraryRequest.CODEC,(p,c)->{var source=c.connection();if(source==null||!source.isConnected())return;c.enqueueWork(()->{if(source.isConnected()&&c.player() instanceof ServerPlayer player&&player.connection.getConnection()==source)CabinetGameLibraryService.handle(player,p);});})
            .playToClient(LibraryReply.TYPE,LibraryReply.CODEC,(p,c)->{var source=c.connection();if(source==null||!source.isConnected())return;c.enqueueWork(()->{var receiver=librarySink;if(receiver!=null&&source.isConnected())receiver.accept(source,p);});})
            .playToServer(Command.TYPE,Command.CODEC,(p,c)->{var source=c.connection();if(source==null||!source.isConnected())return;c.enqueueWork(()->{if(source.isConnected()&&c.player() instanceof ServerPlayer player&&player.connection.getConnection()==source)CabinetSharedGameService.handle(player,p);});})
            .playToClient(Reply.TYPE,Reply.CODEC,(p,c)->{var source=c.connection();if(source==null||!source.isConnected())return;c.enqueueWork(()->{var receiver=sink;if(receiver!=null&&source.isConnected())receiver.reply(source,p);});});
    }
    private static ResourceLocation id(String s){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade",s);}
    private static void writeManifest(RegistryFriendlyByteBuf b,CabinetGameManifest m){b.writeBoolean(m!=null);if(m==null)return;b.writeUtf(m.backend(),128);b.writeVarInt(m.files().size());for(var f:m.files()){b.writeUtf(f.name(),128);b.writeUtf(f.sha256(),64);b.writeVarInt(f.size());}}
    private static CabinetGameManifest readManifest(RegistryFriendlyByteBuf b){if(!b.readBoolean())return null;String backend=b.readUtf(128);int count=b.readVarInt();if(count<1||count>CabinetGameManifest.MAX_FILES)throw new IllegalArgumentException("Game manifest count");var files=new ArrayList<CabinetGameManifest.Entry>();for(int i=0;i<count;i++)files.add(new CabinetGameManifest.Entry(b.readUtf(128),b.readUtf(64),b.readVarInt()));return new CabinetGameManifest(backend,files);}
    private static void writeProfile(RegistryFriendlyByteBuf b,CabinetGameProfile p){b.writeUtf(p.name(),64);b.writeByte(p.players());b.writeByte(p.orientation().ordinal());b.writeByte(p.aspect().ordinal());b.writeVarInt(p.revision());}
    private static CabinetGameProfile readProfile(RegistryFriendlyByteBuf b){String name=b.readUtf(64);int players=b.readUnsignedByte(),orientation=b.readUnsignedByte(),aspect=b.readUnsignedByte(),revision=b.readVarInt();if(orientation>=CabinetGameProfile.Orientation.values().length||aspect>=CabinetGameProfile.Aspect.values().length)throw new IllegalArgumentException("Profile enum");return new CabinetGameProfile(name,players,CabinetGameProfile.Orientation.values()[orientation],CabinetGameProfile.Aspect.values()[aspect],revision);}
    public record LibraryRequest(UUID request,UUID lease,ResourceLocation backend,int offset,String selectedContentId,CabinetGameProfile edit) implements CustomPacketPayload {
        public LibraryRequest(UUID request,UUID lease,ResourceLocation backend,int offset,String selectedContentId){this(request,lease,backend,offset,selectedContentId,null);}
        public LibraryRequest {Objects.requireNonNull(request);Objects.requireNonNull(lease);Objects.requireNonNull(backend);if(backend.toString().length()>128||!CabinetLibraryPage.validOffset(offset)||selectedContentId==null||!selectedContentId.isEmpty()&&(!selectedContentId.matches("[0-9a-f]{64}")||offset!=0)||edit!=null&&selectedContentId.isEmpty())throw new IllegalArgumentException("Invalid cabinet library request");}
        public static final Type<LibraryRequest> TYPE=new Type<>(id("game_library_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,LibraryRequest> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeUUID(p.lease);b.writeUtf(p.backend.toString(),128);b.writeVarInt(p.offset);b.writeUtf(p.selectedContentId,64);b.writeBoolean(p.edit!=null);if(p.edit!=null)writeProfile(b,p.edit);},b->{var request=b.readUUID();var lease=b.readUUID();var backend=ResourceLocation.parse(b.readUtf(128));int offset=b.readVarInt();String content=b.readUtf(64);return new LibraryRequest(request,lease,backend,offset,content,b.readBoolean()?readProfile(b):null);});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record LibraryReply(UUID request,UUID lease,ResourceLocation backend,boolean success,String message,int capabilities,int offset,int total,List<CabinetGameManifest> games,List<CabinetGameProfile> profiles,boolean canEdit) implements CustomPacketPayload {
        public LibraryReply(UUID request,UUID lease,ResourceLocation backend,boolean success,String message,int capabilities,int offset,int total,List<CabinetGameManifest> games){this(request,lease,backend,success,message,capabilities,offset,total,games,Collections.nCopies(games.size(),CabinetGameProfile.EMPTY),false);}
        public LibraryReply {Objects.requireNonNull(request);Objects.requireNonNull(lease);Objects.requireNonNull(backend);if(backend.toString().length()>128||message==null||message.length()>160||capabilities<0||(capabilities&~cn.piq.fcarcade.access.PlayerContentPolicy.ALL)!=0||!CabinetLibraryPage.validOffset(offset)||total<0||total>CabinetLibraryPage.MAX_TOTAL||games==null||games.size()>CabinetLibraryPage.SIZE||offset+games.size()>total||profiles==null||profiles.size()!=games.size())throw new IllegalArgumentException("Invalid cabinet library reply");games=List.copyOf(games);profiles=List.copyOf(profiles);var ids=new HashSet<String>();for(var game:games)if(!game.backend().equals(backend.toString())||!ids.add(game.contentId()))throw new IllegalArgumentException("Invalid catalog game");}
        public static final Type<LibraryReply> TYPE=new Type<>(id("game_library_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,LibraryReply> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.request);b.writeUUID(p.lease);b.writeUtf(p.backend.toString(),128);b.writeBoolean(p.success);b.writeUtf(p.message,160);b.writeVarInt(p.capabilities);b.writeVarInt(p.offset);b.writeVarInt(p.total);b.writeVarInt(p.games.size());for(int i=0;i<p.games.size();i++){writeManifest(b,p.games.get(i));writeProfile(b,p.profiles.get(i));}b.writeBoolean(p.canEdit);},b->{UUID request=b.readUUID(),lease=b.readUUID();var backend=ResourceLocation.parse(b.readUtf(128));boolean success=b.readBoolean();String message=b.readUtf(160);int caps=b.readVarInt(),offset=b.readVarInt(),total=b.readVarInt(),count=b.readVarInt();if(count<0||count>CabinetLibraryPage.SIZE)throw new IllegalArgumentException("Cabinet catalog count");var games=new ArrayList<CabinetGameManifest>();var profiles=new ArrayList<CabinetGameProfile>();for(int i=0;i<count;i++){games.add(Objects.requireNonNull(readManifest(b)));profiles.add(readProfile(b));}return new LibraryReply(request,lease,backend,success,message,caps,offset,total,games,profiles,b.readBoolean());});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Command(UUID transaction,int sequence,int operation,UUID lease,ResourceLocation backend,CabinetGameManifest manifest,int file,int offset,byte[] bytes) implements CustomPacketPayload {
        public Command {
            Objects.requireNonNull(transaction);Objects.requireNonNull(lease);Objects.requireNonNull(backend);
            if(sequence<0||sequence>20000||operation<OPEN||operation>CANCEL||backend.toString().length()>128||file<0||file>=CabinetGameManifest.MAX_FILES||offset<0||offset>CabinetGameManifest.MAX_FILE
                    ||bytes==null||bytes.length>CabinetGameManifest.CHUNK||operation!=PUT&&bytes.length!=0||operation==PUT&&bytes.length==0||operation!=OPEN&&manifest!=null)throw new IllegalArgumentException("Invalid game command");bytes=bytes.clone();
        }
        @Override public byte[] bytes(){return bytes.clone();}
        int dataLength(){return bytes.length;}
        public static final Type<Command> TYPE=new Type<>(id("game_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Command> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.transaction);b.writeVarInt(p.sequence);b.writeByte(p.operation);b.writeUUID(p.lease);b.writeUtf(p.backend.toString(),128);writeManifest(b,p.manifest);b.writeByte(p.file);b.writeVarInt(p.offset);b.writeByteArray(p.bytes);},b->new Command(b.readUUID(),b.readVarInt(),b.readUnsignedByte(),b.readUUID(),ResourceLocation.parse(b.readUtf(128)),readManifest(b),b.readUnsignedByte(),b.readVarInt(),b.readByteArray(CabinetGameManifest.CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Reply(UUID transaction,int sequence,boolean success,CabinetGameManifest manifest,int missingMask,int file,int offset,byte[] bytes,String message) implements CustomPacketPayload {
        public Reply {Objects.requireNonNull(transaction);if(sequence<0||sequence>20000||file<0||file>CabinetGameManifest.MAX_FILES||offset<0||offset>CabinetGameManifest.MAX_FILE||bytes==null||bytes.length>CabinetGameManifest.CHUNK||message==null||message.length()>160
                ||missingMask<0||missingMask>(manifest==null?0:CabinetGameUploadPlan.allFilesMask(manifest.files().size()))||missingMask!=0&&(!success||sequence!=0))throw new IllegalArgumentException("Invalid game reply");bytes=bytes.clone();}
        /** Non-upload-open replies never carry an upload plan. */
        public Reply(UUID transaction,int sequence,boolean success,CabinetGameManifest manifest,int file,int offset,byte[] bytes,String message){this(transaction,sequence,success,manifest,0,file,offset,bytes,message);}
        @Override public byte[] bytes(){return bytes.clone();}
        int dataLength(){return bytes.length;}
        public static final Type<Reply> TYPE=new Type<>(id("game_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.transaction);b.writeVarInt(p.sequence);b.writeBoolean(p.success);writeManifest(b,p.manifest);b.writeVarInt(p.missingMask);b.writeByte(p.file);b.writeVarInt(p.offset);b.writeByteArray(p.bytes);b.writeUtf(p.message,160);},b->new Reply(b.readUUID(),b.readVarInt(),b.readBoolean(),readManifest(b),b.readVarInt(),b.readUnsignedByte(),b.readVarInt(),b.readByteArray(CabinetGameManifest.CHUNK),b.readUtf(160)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
