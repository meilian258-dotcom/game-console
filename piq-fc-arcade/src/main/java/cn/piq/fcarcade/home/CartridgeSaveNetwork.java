package cn.piq.fcarcade.home;

import cn.piq.fcarcade.FcArcadeMod;
import java.util.*;
import java.util.function.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Metadata-only save management; never transfers state bytes, ROMs or filesystem paths. */
public final class CartridgeSaveNetwork {
    public static final int REFRESH=0,RENAME=1,PREPARE_DELETE=2,CONFIRM_DELETE=3,CLOSE=4,MAX_ENTRIES=64;
    public static final UUID ZERO=new UUID(0,0);
    private static Consumer<Reply> receiver=r->{};
    private static BiConsumer<ServerPlayer,Open> opener=(p,r)->reply(p,new Reply(r.editorToken(),r.system(),"",
            "此机型尚未接入实际存档管理；原存档未改动",List.of(),"","",null,true,r.editorToken()));
    private static BiConsumer<ServerPlayer,Request> handler=(p,r)->{};
    private record Handlers(BiConsumer<ServerPlayer,Open> open,BiConsumer<ServerPlayer,Request> request){}
    private static final Map<String,Handlers> ADDONS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID,String> ROUTES=new java.util.concurrent.ConcurrentHashMap<>();
    private CartridgeSaveNetwork(){}
    public static void setClientReceiver(Consumer<Reply> value){receiver=Objects.requireNonNull(value);}
    public static void setServerHandlers(BiConsumer<ServerPlayer,Open> open,BiConsumer<ServerPlayer,Request> request){opener=Objects.requireNonNull(open);handler=Objects.requireNonNull(request);}
    /** Additive routing; registering an addon never replaces FC's existing authorization service. */
    public static void registerServerHandlers(String system,BiConsumer<ServerPlayer,Open> open,BiConsumer<ServerPlayer,Request> request){
        system=system(system);if(system.equals("fc")||system.equals("sfc")||ADDONS.putIfAbsent(system,new Handlers(Objects.requireNonNull(open),Objects.requireNonNull(request)))!=null)throw new IllegalArgumentException("Save handler already registered");
    }
    private static void open(ServerPlayer p,Open r){var h=ADDONS.get(r.system());if(h!=null)h.open.accept(p,r);else if(r.system().equals("fc")||r.system().equals("sfc"))opener.accept(p,r);else reply(p,new Reply(r.editorToken(),r.system(),"","此机型未注册存档管理服务",List.of(),"","",null,true,r.editorToken()));}
    private static void request(ServerPlayer p,Request r){var system=ROUTES.get(r.token());var h=system==null?null:ADDONS.get(system);if(h==null)handler.accept(p,r);else h.request.accept(p,r);}
    public static void forgetRoute(UUID token){ROUTES.remove(token);}
    public static void send(Open r){PacketDistributor.sendToServer(r);}
    public static void send(Request r){PacketDistributor.sendToServer(r);}
    public static void reply(ServerPlayer p,Reply r){if(ADDONS.containsKey(r.system())){if(r.closed())ROUTES.remove(r.token());else{if(ROUTES.size()>=128&&!ROUTES.containsKey(r.token()))throw new IllegalStateException("Save catalog routing full");ROUTES.put(r.token(),r.system());}}PacketDistributor.sendToPlayer(p,r);}
    public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"cartridge-save-2")
                .playToServer(Open.TYPE,Open.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)open(s,p);}))
                .playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)request(s,p);}))
                .playToClient(Reply.TYPE,Reply.CODEC,(p,c)->{if(FMLEnvironment.dist.isClient())c.enqueueWork(()->receiver.accept(p));});
    }
    private static ResourceLocation id(String path){return ResourceLocation.fromNamespaceAndPath(FcArcadeMod.MOD_ID,path);}
    private static String text(String s,int max){if(s==null||s.length()>max||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Save text bounds");return s;}
    private static String system(String s){if(s==null||s.length()>128||(!"fc".equals(s)&&!"sfc".equals(s)&&(s.indexOf(':')<1||ResourceLocation.tryParse(s)==null)))throw new IllegalArgumentException("Save system");return s;}
    private static String hash(String s,boolean empty){if(s==null||!(empty&&s.isEmpty()||s.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("Save version");return s;}
    private static String opaque(String s,boolean empty){if(s==null||!(empty&&s.isEmpty()||s.matches("[a-z0-9-]{1,96}")))throw new IllegalArgumentException("Save identity");return s;}
    private static void uuid(RegistryFriendlyByteBuf b,UUID id){b.writeBoolean(id!=null);if(id!=null)b.writeUUID(id);}
    private static UUID uuid(RegistryFriendlyByteBuf b){return b.readBoolean()?b.readUUID():null;}
    public record Open(String system,UUID editorToken,UUID cartridgeId,int hand,int slot) implements CustomPacketPayload{
        public Open{system=CartridgeSaveNetwork.system(system);Objects.requireNonNull(editorToken);Objects.requireNonNull(cartridgeId);if(hand<0||hand>1||slot<0||slot>40)throw new IllegalArgumentException("Save editor binding");}
        public static final Type<Open> TYPE=new Type<>(id("cartridge_save_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Open> CODEC=StreamCodec.of((b,v)->{b.writeUtf(v.system,128);b.writeUUID(v.editorToken);b.writeUUID(v.cartridgeId);b.writeByte(v.hand);b.writeByte(v.slot);},b->new Open(b.readUtf(128),b.readUUID(),b.readUUID(),b.readUnsignedByte(),b.readUnsignedByte()));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Request(UUID token,int operation,String id,String version,String name,UUID confirmation) implements CustomPacketPayload{
        public Request{Objects.requireNonNull(token);if(operation<REFRESH||operation>CLOSE)throw new IllegalArgumentException("Save operation");id=opaque(id,true);version=hash(version,true);name=text(name,32);
            if(operation==REFRESH||operation==CLOSE){if(!id.isEmpty()||!version.isEmpty()||!name.isEmpty()||confirmation!=null)throw new IllegalArgumentException("Save control fields");}
            else if(id.isEmpty()||version.isEmpty()||operation!=RENAME&&!name.isEmpty()||operation==CONFIRM_DELETE!=(confirmation!=null))throw new IllegalArgumentException("Save action fields");}
        public static final Type<Request> TYPE=new Type<>(CartridgeSaveNetwork.id("cartridge_save_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.token);b.writeByte(v.operation);b.writeUtf(v.id,96);b.writeUtf(v.version,64);b.writeUtf(v.name,32);uuid(b,v.confirmation);},b->new Request(b.readUUID(),b.readUnsignedByte(),b.readUtf(96),b.readUtf(64),b.readUtf(32),uuid(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Entry(String id,String version,String name,String owner,String source,long modified,long bytes,boolean active,boolean canEdit){
        public Entry{id=opaque(id,false);version=hash(version,false);name=text(name,32);owner=text(owner,96);source=text(source,96);if(modified<0||bytes<0)throw new IllegalArgumentException("Save metadata");}
    }
    public record Reply(UUID token,String system,String rom,String message,List<Entry> entries,String pendingId,String pendingVersion,UUID confirmation,boolean closed,UUID editorToken) implements CustomPacketPayload{
        public Reply{Objects.requireNonNull(token);Objects.requireNonNull(editorToken);system=CartridgeSaveNetwork.system(system);rom=hash(rom,true);message=text(message,240);entries=List.copyOf(entries);if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Save list limit");pendingId=opaque(pendingId,true);pendingVersion=hash(pendingVersion,true);if(confirmation==null?(!pendingId.isEmpty()||!pendingVersion.isEmpty()):(pendingId.isEmpty()||pendingVersion.isEmpty()))throw new IllegalArgumentException("Save confirmation fields");}
        public static final Type<Reply> TYPE=new Type<>(id("cartridge_save_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Reply> CODEC=StreamCodec.of((b,v)->{
            b.writeUUID(v.token);b.writeUtf(v.system,128);b.writeUtf(v.rom,64);b.writeUtf(v.message,240);b.writeVarInt(v.entries.size());
            for(var e:v.entries){b.writeUtf(e.id,96);b.writeUtf(e.version,64);b.writeUtf(e.name,32);b.writeUtf(e.owner,96);b.writeUtf(e.source,96);b.writeVarLong(e.modified);b.writeVarLong(e.bytes);b.writeBoolean(e.active);b.writeBoolean(e.canEdit);}
            b.writeUtf(v.pendingId,96);b.writeUtf(v.pendingVersion,64);uuid(b,v.confirmation);b.writeBoolean(v.closed);b.writeUUID(v.editorToken);
        },b->{UUID token=b.readUUID();String system=b.readUtf(128),rom=b.readUtf(64),message=b.readUtf(240);int count=b.readVarInt();if(count<0||count>MAX_ENTRIES)throw new IllegalArgumentException("Save list limit");var list=new ArrayList<Entry>(count);for(int i=0;i<count;i++)list.add(new Entry(b.readUtf(96),b.readUtf(64),b.readUtf(32),b.readUtf(96),b.readUtf(96),b.readVarLong(),b.readVarLong(),b.readBoolean(),b.readBoolean()));return new Reply(token,system,rom,message,list,b.readUtf(96),b.readUtf(64),uuid(b),b.readBoolean(),b.readUUID());});
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
