package cn.piq.sfchome.net;

import cn.piq.sfchome.server.SfcLocalWatchServer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Read-only local observation: separate leases cannot be used as controller/ROM permissions. */
public final class SfcLocalWatchNetwork {
    public static final int MEDIA=0, LOCAL=1, OFF=2;
    private SfcLocalWatchNetwork() {}
    public interface Client {
        boolean acceptsConnection(Object source);
        void start(Start p); void stopped(Stopped p); void capture(Capture p);
        void state(State p); void frames(Frames p); void resume(Resume p); void cancel(Cancel p);
    }
    private static volatile Client client;
    public static void client(Client value) { client=Objects.requireNonNull(value); }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","local_watch_"+name));
    }
    private static void server(net.neoforged.neoforge.network.handling.IPayloadContext c,java.util.function.Consumer<ServerPlayer> action) {
        var source=c.connection(); var sender=c.player(); c.enqueueWork(()->{
            if(sender instanceof ServerPlayer p && p.connection.getConnection()==source && source.isConnected()
                    && p.getServer()!=null && p.getServer().getPlayerList().getPlayer(p.getUUID())==p) action.accept(p);
        });
    }
    private static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext c,java.util.function.Consumer<Client> action) {
        var source=c.connection(); c.enqueueWork(()->{var h=client;if(h!=null&&h.acceptsConnection(source))action.accept(h);});
    }
    public static void register(RegisterPayloadHandlersEvent e) {
        var r=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(e,"sfc-local-watch-1");
        r.playToServer(Preference.TYPE,Preference.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.preference(s,p)));
        r.playToServer(Ready.TYPE,Ready.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.ready(s,p)));
        r.playToServer(Upload.TYPE,Upload.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.upload(s,p)));
        r.playToServer(CaptureFailed.TYPE,CaptureFailed.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.captureFailed(s,p)));
        r.playToServer(Ack.TYPE,Ack.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.ack(s,p)));
        r.playToServer(Digest.TYPE,Digest.CODEC,(p,c)->server(c,s->SfcLocalWatchServer.digest(s,p)));
        r.playToClient(Start.TYPE,Start.CODEC,(p,c)->dispatch(c,h->h.start(p)));
        r.playToClient(Stopped.TYPE,Stopped.CODEC,(p,c)->dispatch(c,h->h.stopped(p)));
        r.playToClient(Capture.TYPE,Capture.CODEC,(p,c)->dispatch(c,h->h.capture(p)));
        r.playToClient(State.TYPE,State.CODEC,(p,c)->dispatch(c,h->h.state(p)));
        r.playToClient(Frames.TYPE,Frames.CODEC,(p,c)->dispatch(c,h->h.frames(p)));
        r.playToClient(Resume.TYPE,Resume.CODEC,(p,c)->dispatch(c,h->h.resume(p)));
        r.playToClient(Cancel.TYPE,Cancel.CODEC,(p,c)->dispatch(c,h->h.cancel(p)));
    }
    public record Preference(long revision,int mode,boolean available) implements CustomPacketPayload {
        public Preference {if(revision<1||mode<MEDIA||mode>OFF)throw new IllegalArgumentException("Observer preference");}
        public static final Type<Preference> TYPE=id("preference");
        public static final StreamCodec<RegistryFriendlyByteBuf,Preference> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeByte(p.mode);b.writeBoolean(p.available);},b->new Preference(b.readVarLong(),b.readUnsignedByte(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Start(long revision,UUID lease,SfcHomeNetwork.Session session,int frame,String sha,String initial,double fps,int exitRange) implements CustomPacketPayload {
        public Start {Objects.requireNonNull(lease);Objects.requireNonNull(session);SfcHomeNetwork.hash(sha,false);SfcHomeNetwork.hash(initial,false);
            if(revision<1||frame<0||session.executionHost()||session.syncMode()!=1||!session.controllerLease().equals(lease)||!Double.isFinite(fps)||fps<49||fps>61||exitRange<1||exitRange>68)throw new IllegalArgumentException("Observer start");}
        public static final Type<Start> TYPE=id("start");
        public static final StreamCodec<RegistryFriendlyByteBuf,Start> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);SfcHomeNetwork.Session.CODEC.encode(b,p.session);b.writeVarInt(p.frame);b.writeUtf(p.sha,64);b.writeUtf(p.initial,64);b.writeDouble(p.fps);b.writeVarInt(p.exitRange);},b->new Start(b.readVarLong(),b.readUUID(),SfcHomeNetwork.Session.CODEC.decode(b),b.readVarInt(),b.readUtf(64),b.readUtf(64),b.readDouble(),b.readVarInt()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Ready(UUID lease,String initial,double fps) implements CustomPacketPayload {
        public Ready {Objects.requireNonNull(lease);SfcHomeNetwork.hash(initial,false);if(!Double.isFinite(fps)||fps<49||fps>61)throw new IllegalArgumentException("Observer ready");}
        public static final Type<Ready> TYPE=id("ready");
        public static final StreamCodec<RegistryFriendlyByteBuf,Ready> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);b.writeUtf(p.initial,64);b.writeDouble(p.fps);},b->new Ready(b.readUUID(),b.readUtf(64),b.readDouble()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Capture(SfcRepairNetwork.Key key) implements CustomPacketPayload {
        public Capture {Objects.requireNonNull(key);} public static final Type<Capture> TYPE=id("capture");
        public static final StreamCodec<RegistryFriendlyByteBuf,Capture> CODEC=StreamCodec.of((b,p)->SfcRepairNetwork.Request.CODEC.encode(b,new SfcRepairNetwork.Request(p.key)),b->new Capture(SfcRepairNetwork.Request.CODEC.decode(b).key()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record CaptureFailed(SfcRepairNetwork.Key key) implements CustomPacketPayload {
        public CaptureFailed {Objects.requireNonNull(key);}public static final Type<CaptureFailed> TYPE=id("capture_failed");
        public static final StreamCodec<RegistryFriendlyByteBuf,CaptureFailed> CODEC=StreamCodec.of((b,p)->SfcRepairNetwork.Request.CODEC.encode(b,new SfcRepairNetwork.Request(p.key)),b->new CaptureFailed(SfcRepairNetwork.Request.CODEC.decode(b).key()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record State(SfcRepairNetwork.State value) implements CustomPacketPayload {
        public State {Objects.requireNonNull(value);} public static final Type<State> TYPE=id("state");
        public static final StreamCodec<RegistryFriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->SfcRepairNetwork.State.CODEC.encode(b,p.value),b->new State(SfcRepairNetwork.State.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Upload(SfcRepairNetwork.State value) implements CustomPacketPayload {
        public Upload {Objects.requireNonNull(value);} public static final Type<Upload> TYPE=id("upload");
        public static final StreamCodec<RegistryFriendlyByteBuf,Upload> CODEC=StreamCodec.of((b,p)->SfcRepairNetwork.State.CODEC.encode(b,p.value),b->new Upload(SfcRepairNetwork.State.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Frames(UUID lease,SfcRepairNetwork.Replay value) implements CustomPacketPayload {
        public Frames {Objects.requireNonNull(lease);Objects.requireNonNull(value);if(!lease.equals(value.key().lease())||!lease.equals(value.key().token()))throw new IllegalArgumentException("Observer frame lease");}
        public static final Type<Frames> TYPE=id("frames");
        public static final StreamCodec<RegistryFriendlyByteBuf,Frames> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);SfcRepairNetwork.Replay.CODEC.encode(b,p.value);},b->new Frames(b.readUUID(),SfcRepairNetwork.Replay.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Resume(SfcRepairNetwork.Key key) implements CustomPacketPayload {
        public Resume {Objects.requireNonNull(key);} public static final Type<Resume> TYPE=id("resume");
        public static final StreamCodec<RegistryFriendlyByteBuf,Resume> CODEC=StreamCodec.of((b,p)->SfcRepairNetwork.Resume.CODEC.encode(b,new SfcRepairNetwork.Resume(p.key)),b->new Resume(SfcRepairNetwork.Resume.CODEC.decode(b).key()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** phase 0: restored, 1: caught up, 2: release/failure. No gameplay commands. */
    public record Ack(UUID lease,int phase,boolean success) implements CustomPacketPayload {
        public Ack {Objects.requireNonNull(lease);if(phase<0||phase>2)throw new IllegalArgumentException("Observer ack");}
        public static final Type<Ack> TYPE=id("ack");
        public static final StreamCodec<RegistryFriendlyByteBuf,Ack> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);b.writeByte(p.phase);b.writeBoolean(p.success);},b->new Ack(b.readUUID(),b.readUnsignedByte(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Digest(UUID lease,int frame,String sha) implements CustomPacketPayload {
        public Digest {Objects.requireNonNull(lease);SfcHomeNetwork.hash(sha,false);if(frame<1||frame%600!=0)throw new IllegalArgumentException("Observer digest interval");}
        public static final Type<Digest> TYPE=id("digest");
        public static final StreamCodec<RegistryFriendlyByteBuf,Digest> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);b.writeVarInt(p.frame);b.writeUtf(p.sha,64);},b->new Digest(b.readUUID(),b.readVarInt(),b.readUtf(64)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Stopped(long revision,UUID lease,String reason) implements CustomPacketPayload {
        public Stopped {Objects.requireNonNull(lease);if(revision<1||reason==null||reason.length()>192||reason.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Observer stopped");}
        public static final Type<Stopped> TYPE=id("stopped");
        public static final StreamCodec<RegistryFriendlyByteBuf,Stopped> CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.revision);b.writeUUID(p.lease);b.writeUtf(p.reason,192);},b->new Stopped(b.readVarLong(),b.readUUID(),b.readUtf(192)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Cancel(UUID lease) implements CustomPacketPayload {
        public Cancel {Objects.requireNonNull(lease);}public static final Type<Cancel> TYPE=id("cancel");
        public static final StreamCodec<RegistryFriendlyByteBuf,Cancel> CODEC=StreamCodec.of((b,p)->b.writeUUID(p.lease),b->new Cancel(b.readUUID()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
