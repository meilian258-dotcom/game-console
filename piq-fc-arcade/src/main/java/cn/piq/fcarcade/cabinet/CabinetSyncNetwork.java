package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Lease + connection + epoch guarded synchronous lane; spectators have none of these capabilities. */
public final class CabinetSyncNetwork {
    private CabinetSyncNetwork(){}
    public interface ClientSink {
        boolean acceptsConnection(Connection source);
        void frames(Frames packet);void restore(Restore packet);void active(Active packet);void uploadGrant(UploadGrant packet);
    }
    private static volatile ClientSink sink;
    private static volatile Consumer<Setting> settings;
    public static void setClientSink(ClientSink value){sink=Objects.requireNonNull(value);}
    public static void setSettingsSink(Consumer<Setting> value){settings=value;}
    public static void requestMode(CabinetTarget target,ResourceLocation backend,int mode){requestMode(target,backend,mode,null);}
    public static void requestMode(CabinetTarget target,ResourceLocation backend,int mode,UUID debugToken){PacketDistributor.sendToServer(new Mode(target,backend,mode,debugToken));}
    public static void requestCoinMode(CabinetTarget target,ResourceLocation backend,boolean required,UUID debugToken){PacketDistributor.sendToServer(new Mode(target,backend,-1,debugToken,required?1:0));}
    public static void requestAutoPowerOff(CabinetTarget target,ResourceLocation backend,boolean value,UUID debugToken){PacketDistributor.sendToServer(new Mode(target,backend,-1,debugToken,-1,value?1:0));}
    public static void requestPowerSettings(CabinetTarget target,ResourceLocation backend,boolean enabled,int seconds,int range,UUID token){PacketDistributor.sendToServer(new Mode(target,backend,-1,token,-1,enabled?1:0,seconds,range));}
    static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cabinet-sync-10")
            .playToServer(Hello.TYPE,Hello.CODEC,(p,c)->server(c,s->CabinetSynchronizer.hello(s,p)))
            .playToServer(Input.TYPE,Input.CODEC,(p,c)->server(c,s->CabinetSynchronizer.input(s,p)))
            .playToServer(Upload.TYPE,Upload.CODEC,(p,c)->server(c,s->CabinetSynchronizer.upload(s,p)))
            .playToServer(Ack.TYPE,Ack.CODEC,(p,c)->server(c,s->CabinetSynchronizer.ack(s,p)))
            .playToServer(Digest.TYPE,Digest.CODEC,(p,c)->server(c,s->CabinetSynchronizer.digest(s,p)))
            .playToServer(Resync.TYPE,Resync.CODEC,(p,c)->server(c,s->CabinetSynchronizer.resync(s,p)))
            .playToServer(Mode.TYPE,Mode.CODEC,(p,c)->server(c,s->CabinetSyncSettings.request(s,p)))
            .playToClient(Frames.TYPE,Frames.CODEC,(p,c)->client(c,s->s.frames(p)))
            .playToClient(Restore.TYPE,Restore.CODEC,(p,c)->client(c,s->s.restore(p)))
            .playToClient(Active.TYPE,Active.CODEC,(p,c)->client(c,s->s.active(p)))
            .playToClient(UploadGrant.TYPE,UploadGrant.CODEC,(p,c)->client(c,s->s.uploadGrant(p)))
            .playToClient(Setting.TYPE,Setting.CODEC,(p,c)->client(c,s->{var callback=settings;if(callback!=null)callback.accept(p);}));
    }
    private static void server(IPayloadContext c,Consumer<ServerPlayer> action){
        Connection source=c.connection();var player=c.player();
        c.enqueueWork(()->{if(player instanceof ServerPlayer s&&source!=null&&source.isConnected()&&s.connection.getConnection()==source
                &&!s.hasDisconnected()&&s.getServer()!=null&&s.getServer().getPlayerList().getPlayer(s.getUUID())==s)action.accept(s);});
    }
    private static void client(IPayloadContext c,Consumer<ClientSink> action){Connection source=c.connection();c.enqueueWork(()->{var current=sink;if(source!=null&&source.isConnected()&&current!=null&&current.acceptsConnection(source))action.accept(current);});}
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","cabinet_sync_"+name);}
    private static void writeDebugToken(RegistryFriendlyByteBuf b,UUID token){b.writeBoolean(token!=null);if(token!=null)b.writeUUID(token);}
    private static UUID readDebugToken(RegistryFriendlyByteBuf b){return b.readBoolean()?b.readUUID():null;}
    private static void scope(UUID room,UUID member,int epoch){Objects.requireNonNull(room);Objects.requireNonNull(member);if(epoch!=1)throw new IllegalArgumentException("Invalid room epoch");}
    private static void hash(String h){if(!CabinetSyncState.validHash(h))throw new IllegalArgumentException("Invalid hash");}
    private static void scope(RegistryFriendlyByteBuf b,UUID room,UUID member,int epoch){b.writeUUID(room);b.writeUUID(member);b.writeVarInt(epoch);}
    public record Hello(UUID room,UUID member,int epoch,String romHash,String contentId,String compatibility,int fpsMilli,String initialHash)implements CustomPacketPayload{
        public Hello{scope(room,member,epoch);new CabinetSyncPolicy.Identity(romHash,contentId,compatibility,fpsMilli,initialHash);}
        public CabinetSyncPolicy.Identity identity(){return new CabinetSyncPolicy.Identity(romHash,contentId,compatibility,fpsMilli,initialHash);}
        public static final Type<Hello> TYPE=new Type<>(id("hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Hello> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeUtf(p.romHash,64);b.writeUtf(p.contentId,64);b.writeUtf(p.compatibility,256);b.writeVarInt(p.fpsMilli);b.writeUtf(p.initialHash,64);},b->new Hello(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUtf(64),b.readUtf(64),b.readUtf(256),b.readVarInt(),b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Input(UUID room,UUID member,int epoch,long sequence,int mask,boolean reset)implements CustomPacketPayload{
        public Input{scope(room,member,epoch);if(sequence<0||(mask&~4095)!=0||reset&&mask!=0)throw new IllegalArgumentException("Invalid sync input");}
        public static final Type<Input> TYPE=new Type<>(id("input"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Input> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeVarLong(p.sequence);b.writeVarInt(p.mask);b.writeBoolean(p.reset);},b->new Input(b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarLong(),b.readVarInt(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Frames(UUID room,UUID member,int epoch,List<CabinetSyncTimeline.Step> steps)implements CustomPacketPayload{
        public Frames{scope(room,member,epoch);steps=List.copyOf(steps);if(steps.isEmpty()||steps.size()>CabinetSyncTimeline.MAX_BATCH)throw new IllegalArgumentException("Frame batch");long f=steps.getFirst().frame();for(var s:steps)if(s.frame()!=f++)throw new IllegalArgumentException("Frame gap");}
        public static final Type<Frames> TYPE=new Type<>(id("frames"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Frames> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeVarInt(p.steps.size());b.writeVarLong(p.steps.getFirst().frame());for(var s:p.steps){b.writeVarInt(s.p1());b.writeVarInt(s.p2());b.writeVarInt(s.p3());b.writeVarInt(s.p4());}},b->{UUID r=b.readUUID(),m=b.readUUID();int e=b.readVarInt(),n=b.readVarInt();if(n<1||n>CabinetSyncTimeline.MAX_BATCH)throw new IllegalArgumentException("Frame batch");long f=b.readVarLong();var list=new ArrayList<CabinetSyncTimeline.Step>(n);for(int i=0;i<n;i++)list.add(new CabinetSyncTimeline.Step(f+i,b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt()));return new Frames(r,m,e,list);});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record StatePart(UUID room,UUID member,int epoch,UUID token,long frame,long goal,int total,int offset,String hash,boolean begin,byte[] bytes){
        public StatePart{scope(room,member,epoch);Objects.requireNonNull(token);CabinetSyncNetwork.hash(hash);if(frame<0||goal<frame||total<1||total>CabinetSyncCore.MAX_STATE_BYTES||offset<0||offset>total||bytes==null||bytes.length>CabinetSyncState.CHUNK||bytes.length>total-offset||begin&&(offset!=0||bytes.length!=0)||!begin&&bytes.length==0)throw new IllegalArgumentException("Invalid state chunk");bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
        static final StreamCodec<RegistryFriendlyByteBuf,StatePart> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeUUID(p.token);b.writeVarLong(p.frame);b.writeVarLong(p.goal);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeUtf(p.hash,64);b.writeBoolean(p.begin);b.writeByteArray(p.bytes);},b->new StatePart(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUUID(),b.readVarLong(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readUtf(64),b.readBoolean(),b.readByteArray(CabinetSyncState.CHUNK)));
    }
    public record Upload(StatePart part)implements CustomPacketPayload{
        public Upload{Objects.requireNonNull(part);}public static final Type<Upload> TYPE=new Type<>(id("upload"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Upload> CODEC=StatePart.CODEC.map(Upload::new,Upload::part);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Restore(StatePart part)implements CustomPacketPayload{
        public Restore{Objects.requireNonNull(part);}public static final Type<Restore> TYPE=new Type<>(id("restore"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Restore> CODEC=StatePart.CODEC.map(Restore::new,Restore::part);
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Ack(UUID room,UUID member,int epoch,UUID token,long frame)implements CustomPacketPayload{
        public Ack{scope(room,member,epoch);Objects.requireNonNull(token);if(frame<0)throw new IllegalArgumentException("Frame");}public static final Type<Ack> TYPE=new Type<>(id("ack"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Ack> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeUUID(p.token);b.writeVarLong(p.frame);},b->new Ack(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUUID(),b.readVarLong()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Active(UUID room,UUID member,int epoch,UUID token)implements CustomPacketPayload{
        public Active{scope(room,member,epoch);Objects.requireNonNull(token);}public static final Type<Active> TYPE=new Type<>(id("active"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Active> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeUUID(p.token);},b->new Active(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Digest(UUID room,UUID member,int epoch,long frame,String hash)implements CustomPacketPayload{
        public Digest{scope(room,member,epoch);CabinetSyncNetwork.hash(hash);if(frame<1||frame%300!=0)throw new IllegalArgumentException("Digest frame");}public static final Type<Digest> TYPE=new Type<>(id("digest"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Digest> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeVarLong(p.frame);b.writeUtf(p.hash,64);},b->new Digest(b.readUUID(),b.readUUID(),b.readVarInt(),b.readVarLong(),b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record UploadGrant(UUID room,UUID member,int epoch,UUID token)implements CustomPacketPayload{
        public UploadGrant{scope(room,member,epoch);Objects.requireNonNull(token);}public static final Type<UploadGrant> TYPE=new Type<>(id("upload_grant"));
        public static final StreamCodec<RegistryFriendlyByteBuf,UploadGrant> CODEC=StreamCodec.of((b,p)->{scope(b,p.room,p.member,p.epoch);b.writeUUID(p.token);},b->new UploadGrant(b.readUUID(),b.readUUID(),b.readVarInt(),b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Resync(UUID room,UUID member,int epoch)implements CustomPacketPayload{
        public Resync{scope(room,member,epoch);}public static final Type<Resync> TYPE=new Type<>(id("resync"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Resync> CODEC=StreamCodec.of((b,p)->scope(b,p.room,p.member,p.epoch),b->new Resync(b.readUUID(),b.readUUID(),b.readVarInt()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Mode(CabinetTarget target,ResourceLocation backend,int mode,UUID debugToken,int coinMode,int autoPowerOff,int idleSeconds,int renderDistance)implements CustomPacketPayload{
        public Mode(CabinetTarget target,ResourceLocation backend,int mode,UUID debugToken,int coinMode,int autoPowerOff){this(target,backend,mode,debugToken,coinMode,autoPowerOff,-1,-1);}
        public Mode(CabinetTarget target,ResourceLocation backend,int mode,UUID debugToken,int coinMode){this(target,backend,mode,debugToken,coinMode,-1);}
        public Mode(CabinetTarget target,ResourceLocation backend,int mode,UUID debugToken){this(target,backend,mode,debugToken,-1);}
        public Mode(CabinetTarget target,ResourceLocation backend,int mode){this(target,backend,mode,null);}
        public Mode{Objects.requireNonNull(target);Objects.requireNonNull(backend);if(mode< -1||mode>6||coinMode< -1||coinMode>1||autoPowerOff< -1||autoPowerOff>1||(mode>=0?1:0)+(coinMode>=0?1:0)+(autoPowerOff>=0?1:0)>1||backend.toString().length()>128||idleSeconds!=-1&&!CabinetPowerSettings.validSeconds(idleSeconds)||renderDistance!=-1&&!CabinetPowerSettings.validRenderDistance(renderDistance)||(idleSeconds!=-1||renderDistance!=-1)&&autoPowerOff<0)throw new IllegalArgumentException("Mode");}public static final Type<Mode> TYPE=new Type<>(id("mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Mode> CODEC=StreamCodec.of((b,p)->{CabinetNetwork.writeTarget(b,p.target);b.writeUtf(p.backend.toString(),128);b.writeInt(p.mode);writeDebugToken(b,p.debugToken);b.writeByte(p.coinMode);b.writeByte(p.autoPowerOff);b.writeInt(p.idleSeconds);b.writeInt(p.renderDistance);},b->new Mode(CabinetNetwork.readTarget(b),ResourceLocation.parse(b.readUtf(128)),b.readInt(),readDebugToken(b),b.readByte(),b.readByte(),b.readInt(),b.readInt()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken,boolean coinRequired,boolean coinEditable,String gameInfo,boolean autoPowerOff,int idleSeconds,int renderDistance,int saveMode)implements CustomPacketPayload{
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken,boolean coinRequired,boolean coinEditable,String gameInfo,boolean autoPowerOff,int idleSeconds,int renderDistance){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,debugToken,coinRequired,coinEditable,gameInfo,autoPowerOff,idleSeconds,renderDistance,0);}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken,boolean coinRequired,boolean coinEditable,String gameInfo,boolean autoPowerOff){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,debugToken,coinRequired,coinEditable,gameInfo,autoPowerOff,CabinetPowerSettings.DEFAULT_SECONDS,CabinetPowerSettings.DEFAULT_RENDER_DISTANCE);}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken,boolean coinRequired,boolean coinEditable,String gameInfo){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,debugToken,coinRequired,coinEditable,gameInfo,false);}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken,boolean coinRequired,boolean coinEditable){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,debugToken,coinRequired,coinEditable,"");}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason,UUID debugToken){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,debugToken,false,false);}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason,boolean hosted,boolean playerMedia,String hostedReason){this(target,backend,mode,supported,editable,reason,hosted,playerMedia,hostedReason,null);}
        public Setting(CabinetTarget target,ResourceLocation backend,int mode,boolean supported,boolean editable,String reason){this(target,backend,mode,supported,editable,reason,false,true,"服务器未启用托管");}
        public Setting{CabinetGameInfo.check(gameInfo);Objects.requireNonNull(target);Objects.requireNonNull(backend);if(saveMode<0||saveMode>2||mode<0||mode>3||!CabinetPowerSettings.validSeconds(idleSeconds)||!CabinetPowerSettings.validRenderDistance(renderDistance))throw new IllegalArgumentException("Mode");if(reason==null||reason.length()>256||hostedReason==null||hostedReason.length()>256
                ||(coinRequired||coinEditable)&&!CabinetCoinPolicy.supported(backend.toString())||coinEditable&&debugToken==null)throw new IllegalArgumentException("Mode reason/coin authority");}public static final Type<Setting> TYPE=new Type<>(id("setting"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Setting> CODEC=StreamCodec.of((b,p)->{CabinetNetwork.writeTarget(b,p.target);b.writeUtf(p.backend.toString(),128);b.writeVarInt(p.mode);b.writeBoolean(p.supported);b.writeBoolean(p.editable);b.writeUtf(p.reason,256);b.writeBoolean(p.hosted);b.writeBoolean(p.playerMedia);b.writeUtf(p.hostedReason,256);writeDebugToken(b,p.debugToken);b.writeBoolean(p.coinRequired);b.writeBoolean(p.coinEditable);b.writeUtf(p.gameInfo,192);b.writeBoolean(p.autoPowerOff);b.writeInt(p.idleSeconds);b.writeInt(p.renderDistance);b.writeByte(p.saveMode);},b->new Setting(CabinetNetwork.readTarget(b),ResourceLocation.parse(b.readUtf(128)),b.readVarInt(),b.readBoolean(),b.readBoolean(),b.readUtf(256),b.readBoolean(),b.readBoolean(),b.readUtf(256),readDebugToken(b),b.readBoolean(),b.readBoolean(),b.readUtf(192),b.readBoolean(),b.readInt(),b.readInt(),b.readUnsignedByte()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
