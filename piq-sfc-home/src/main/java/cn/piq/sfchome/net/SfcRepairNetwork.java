package cn.piq.sfchome.net;

import cn.piq.sfchome.server.SfcHomeServer;
import cn.piq.sfchome.server.SfcRepairLedger;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Separate mandatory protocol: old clients must never silently omit consistency checks. */
public final class SfcRepairNetwork {
    public static final UUID DIGEST_TOKEN=new UUID(0,0);
    private SfcRepairNetwork(){}
    public interface Client{boolean acceptsConnection(Object source);void begin(Begin p);void request(Request p);void state(State p);void replay(Replay p);void resume(Resume p);void cancel(Cancel p);}
    private static volatile Client client;
    public static void client(Client value){client=Objects.requireNonNull(value);}
    private static <T extends CustomPacketPayload>CustomPacketPayload.Type<T>id(String name){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","repair_"+name));}
    public record Key(long session,int epoch,UUID lease,UUID token,int frame){public Key{if(session<=0||epoch<=0||frame<0)throw new IllegalArgumentException("Invalid repair identity");Objects.requireNonNull(lease);Objects.requireNonNull(token);}}
    private static void write(RegistryFriendlyByteBuf b,Key k){b.writeVarLong(k.session);b.writeVarInt(k.epoch);b.writeUUID(k.lease);b.writeUUID(k.token);b.writeVarInt(k.frame);}
    private static Key read(RegistryFriendlyByteBuf b){return new Key(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readUUID(),b.readVarInt());}
    private static void sha(String sha,boolean blank){if(!(blank&&"".equals(sha))&&!SfcRepairLedger.hash(sha))throw new IllegalArgumentException("Invalid repair digest");}
    private static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext c,java.util.function.Consumer<Client> action){Object source=c.connection();c.enqueueWork(()->{var h=client;if(h!=null&&h.acceptsConnection(source))action.accept(h);});}
    private static void server(net.neoforged.neoforge.network.handling.IPayloadContext c,java.util.function.Consumer<ServerPlayer> action){var source=c.connection();var sender=c.player();c.enqueueWork(()->{if(sender instanceof ServerPlayer s&&s.connection.getConnection()==source&&source.isConnected()&&s.getServer()!=null&&s.getServer().getPlayerList().getPlayer(s.getUUID())==s)action.accept(s);});}
    public static void register(RegisterPayloadHandlersEvent e){var r=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(e,"sfc-repair-2");
        r.playToServer(Digest.TYPE,Digest.CODEC,(p,c)->server(c,s->SfcHomeServer.repairDigest(s,p)));
        r.playToServer(Fault.TYPE,Fault.CODEC,(p,c)->server(c,s->SfcHomeServer.repairFault(s,p)));
        r.playToServer(Upload.TYPE,Upload.CODEC,(p,c)->server(c,s->SfcHomeServer.repairUpload(s,p.state)));
        r.playToServer(Restored.TYPE,Restored.CODEC,(p,c)->server(c,s->SfcHomeServer.repairRestored(s,p)));
        r.playToServer(Done.TYPE,Done.CODEC,(p,c)->server(c,s->SfcHomeServer.repairDone(s,p)));
        r.playToClient(Begin.TYPE,Begin.CODEC,(p,c)->dispatch(c,h->h.begin(p)));r.playToClient(Request.TYPE,Request.CODEC,(p,c)->dispatch(c,h->h.request(p)));
        r.playToClient(State.TYPE,State.CODEC,(p,c)->dispatch(c,h->h.state(p)));r.playToClient(Replay.TYPE,Replay.CODEC,(p,c)->dispatch(c,h->h.replay(p)));
        r.playToClient(Resume.TYPE,Resume.CODEC,(p,c)->dispatch(c,h->h.resume(p)));r.playToClient(Cancel.TYPE,Cancel.CODEC,(p,c)->dispatch(c,h->h.cancel(p)));
    }
    public record Digest(Key key,String sha)implements CustomPacketPayload{public Digest{Objects.requireNonNull(key);SfcRepairNetwork.sha(sha,false);if(!key.token.equals(DIGEST_TOKEN)||key.frame<1||key.frame%SfcRepairLedger.INTERVAL!=0)throw new IllegalArgumentException("Digest interval");}public static final Type<Digest>TYPE=id("digest");public static final StreamCodec<RegistryFriendlyByteBuf,Digest>CODEC=StreamCodec.of((b,p)->{write(b,p.key);b.writeUtf(p.sha,64);},b->new Digest(read(b),b.readUtf(64)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Fault(Key key)implements CustomPacketPayload{public Fault{Objects.requireNonNull(key);if(!key.token.equals(DIGEST_TOKEN))throw new IllegalArgumentException("Fault nonce");}public static final Type<Fault>TYPE=id("fault");public static final StreamCodec<RegistryFriendlyByteBuf,Fault>CODEC=StreamCodec.of((b,p)->write(b,p.key),b->new Fault(read(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Begin(Key key)implements CustomPacketPayload{public Begin{Objects.requireNonNull(key);}public static final Type<Begin>TYPE=id("begin");public static final StreamCodec<RegistryFriendlyByteBuf,Begin>CODEC=StreamCodec.of((b,p)->write(b,p.key),b->new Begin(read(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Request(Key key)implements CustomPacketPayload{public Request{Objects.requireNonNull(key);}public static final Type<Request>TYPE=id("request");public static final StreamCodec<RegistryFriendlyByteBuf,Request>CODEC=StreamCodec.of((b,p)->write(b,p.key),b->new Request(read(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record State(Key key,int total,int offset,String sha,byte[] data)implements CustomPacketPayload{
        public State{Objects.requireNonNull(key);SfcRepairNetwork.sha(sha,false);if(total<1||total>SfcRepairLedger.MAX_STATE||offset<0||data==null||data.length<1||data.length>SfcRepairLedger.CHUNK||(long)offset+data.length>total)throw new IllegalArgumentException("Repair chunk bounds");data=data.clone();}
        @Override public byte[]data(){return data.clone();}public static final Type<State>TYPE=id("state");
        public static final StreamCodec<RegistryFriendlyByteBuf,State>CODEC=StreamCodec.of((b,p)->{write(b,p.key);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeUtf(p.sha,64);b.writeByteArray(p.data);},b->new State(read(b),b.readVarInt(),b.readVarInt(),b.readUtf(64),b.readByteArray(SfcRepairLedger.CHUNK)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Upload(State state)implements CustomPacketPayload{public Upload{Objects.requireNonNull(state);}public static final Type<Upload>TYPE=id("upload");public static final StreamCodec<RegistryFriendlyByteBuf,Upload>CODEC=StreamCodec.of((b,p)->State.CODEC.encode(b,p.state),b->new Upload(State.CODEC.decode(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Restored(Key key,String sha,boolean success)implements CustomPacketPayload{public Restored{Objects.requireNonNull(key);SfcRepairNetwork.sha(sha,true);}public static final Type<Restored>TYPE=id("restored");public static final StreamCodec<RegistryFriendlyByteBuf,Restored>CODEC=StreamCodec.of((b,p)->{write(b,p.key);b.writeUtf(p.sha,64);b.writeBoolean(p.success);},b->new Restored(read(b),b.readUtf(64),b.readBoolean()));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Replay(Key key,int[]p1,int[]p2)implements CustomPacketPayload{
        public Replay{Objects.requireNonNull(key);if(p1==null||p2==null||p1.length<1||p1.length>SfcRepairLedger.BATCH||p1.length!=p2.length)throw new IllegalArgumentException("Replay bounds");p1=p1.clone();p2=p2.clone();for(int i=0;i<p1.length;i++)if(p1[i]<0||p1[i]>4095||p2[i]<0||p2[i]>4095)throw new IllegalArgumentException("Replay input");}
        @Override public int[]p1(){return p1.clone();}@Override public int[]p2(){return p2.clone();}public static final Type<Replay>TYPE=id("replay");
        public static final StreamCodec<RegistryFriendlyByteBuf,Replay>CODEC=StreamCodec.of((b,p)->{write(b,p.key);b.writeVarInt(p.p1.length);for(int i=0;i<p.p1.length;i++){b.writeVarInt(p.p1[i]);b.writeVarInt(p.p2[i]);}},b->{Key k=read(b);int n=b.readVarInt();if(n<1||n>SfcRepairLedger.BATCH)throw new IllegalArgumentException("Replay overflow");int[]a=new int[n],d=new int[n];for(int i=0;i<n;i++){a[i]=b.readVarInt();d[i]=b.readVarInt();}return new Replay(k,a,d);});public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Resume(Key key)implements CustomPacketPayload{public Resume{Objects.requireNonNull(key);}public static final Type<Resume>TYPE=id("resume");public static final StreamCodec<RegistryFriendlyByteBuf,Resume>CODEC=StreamCodec.of((b,p)->write(b,p.key),b->new Resume(read(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Done(Key key,boolean success)implements CustomPacketPayload{public Done{Objects.requireNonNull(key);}public static final Type<Done>TYPE=id("done");public static final StreamCodec<RegistryFriendlyByteBuf,Done>CODEC=StreamCodec.of((b,p)->{write(b,p.key);b.writeBoolean(p.success);},b->new Done(read(b),b.readBoolean()));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
    public record Cancel(Key key)implements CustomPacketPayload{public Cancel{Objects.requireNonNull(key);}public static final Type<Cancel>TYPE=id("cancel");public static final StreamCodec<RegistryFriendlyByteBuf,Cancel>CODEC=StreamCodec.of((b,p)->write(b,p.key),b->new Cancel(read(b)));public Type<? extends CustomPacketPayload>type(){return TYPE;}}
}
