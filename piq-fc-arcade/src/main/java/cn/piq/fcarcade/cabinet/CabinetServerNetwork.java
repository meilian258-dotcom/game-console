package cn.piq.fcarcade.cabinet;

import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Small settings snapshots on login/change, not a game stream or per-tick broadcast. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class CabinetServerNetwork {
    public interface ClientSink {void receive(Connection source,State state);}
    public static final UUID PUSH=new UUID(0,0);
    private static volatile ClientSink sink;
    private static final Map<ServerPlayer,Long> LAST=new WeakHashMap<>();
    private CabinetServerNetwork(){}
    public static void clientSink(ClientSink value){sink=Objects.requireNonNull(value);}
    public static void send(Request request){PacketDistributor.sendToServer(request);}
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","arcade_server_"+name);}
    public record Request(UUID nonce,int hand,boolean save,long expectedRevision,CabinetServerRules rules) implements CustomPacketPayload {
        public Request {Objects.requireNonNull(nonce);Objects.requireNonNull(rules);if(hand<0||hand>1||expectedRevision<0||PUSH.equals(nonce))throw new IllegalArgumentException("Arcade rules request");}
        public static final Type<Request> TYPE=new Type<>(id("request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.nonce);b.writeByte(p.hand);b.writeBoolean(p.save);b.writeVarLong(p.expectedRevision);writeRules(b,p.rules);
        },b->new Request(b.readUUID(),b.readUnsignedByte(),b.readBoolean(),b.readVarLong(),readRules(b)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record State(UUID nonce,long revision,CabinetServerRules rules,boolean editable,String message) implements CustomPacketPayload {
        public State {Objects.requireNonNull(nonce);Objects.requireNonNull(rules);Objects.requireNonNull(message);if(revision<1||message.length()>192)throw new IllegalArgumentException("Arcade rules state");}
        public static final Type<State> TYPE=new Type<>(id("state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{
            b.writeUUID(p.nonce);b.writeVarLong(p.revision);writeRules(b,p.rules);b.writeBoolean(p.editable);b.writeUtf(p.message,192);
        },b->new State(b.readUUID(),b.readVarLong(),readRules(b),b.readBoolean(),b.readUtf(192)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static void writeRules(RegistryFriendlyByteBuf b,CabinetServerRules r){b.writeBoolean(r.immediateOnExit());b.writeVarInt(r.idleSeconds());b.writeVarInt(r.range());}
    private static CabinetServerRules readRules(RegistryFriendlyByteBuf b){return new CabinetServerRules(b.readBoolean(),b.readVarInt(),b.readVarInt());}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"arcade-server-rules-1")
            .playToServer(Request.TYPE,Request.CODEC,(p,c)->{var source=c.connection();var actor=c.player();c.enqueueWork(()->{
                if(actor instanceof ServerPlayer player&&source!=null&&source.isConnected()&&source==player.connection.getConnection())handle(player,p);
            });}).playToClient(State.TYPE,State.CODEC,(p,c)->{var source=c.connection();c.enqueueWork(()->{var s=sink;if(s!=null&&source!=null&&source.isConnected())s.receive(source,p);});});
    }
    private static boolean authorized(ServerPlayer p,int hand){
        return p.getServer()!=null&&p.getServer().isSameThread()&&!p.hasDisconnected()&&p.connection.getConnection().isConnected()
            &&p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&p.isAlive()&&!p.isSpectator()&&p.hasPermissions(2)
            &&p.getItemInHand(InteractionHand.values()[hand]).is(cn.piq.fcarcade.registry.ModItems.ADMIN_TERMINAL.get());
    }
    private static void handle(ServerPlayer p,Request request){
        long now=System.nanoTime();Long last=LAST.get(p);if(last!=null&&now-last>=0&&now-last<200_000_000L)return;LAST.put(p,now);
        boolean allowed=authorized(p,request.hand());String message=allowed?"":"仅 OP 手持管理终端时可以设置。";
        if(allowed&&request.save())message=CabinetServerSettings.update(p.getServer(),request.expectedRevision(),request.rules())
            ?"已统一应用到全部街机；规则变化时空席重新计时。":"设置已被其他管理员修改，请刷新后重试。";
        sendState(p,request.nonce(),allowed,message);
    }
    private static void sendState(ServerPlayer p,UUID nonce,boolean editable,String message){
        PacketDistributor.sendToPlayer(p,new State(nonce,CabinetServerSettings.revision(p.getServer()),CabinetServerSettings.rules(p.getServer()),editable,message));
    }
    static void broadcast(MinecraftServer server){for(var p:server.getPlayerList().getPlayers())sendState(p,PUSH,false,"");}
    @EventBusSubscriber(modid="piq_fc_arcade")
    public static final class Events {
        @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p)sendState(p,PUSH,false,"");}
        @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p)LAST.remove(p);}
    }
}
