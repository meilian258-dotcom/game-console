package cn.piq.sfchome.net;

import cn.piq.sfchome.server.SfcHomeServer;
import cn.piq.sfchome.server.SfcJoinGate;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Consent and state-transfer records retain their format; registrar 5 requires the separated runtime Session. */
public final class SfcJoinNetwork {
    private SfcJoinNetwork(){}
    public interface Client {void offer(Offer p);void approval(Approval p);void capture(Capture p);void state(State p);void result(Result p);default boolean acceptsConnection(Object source){return false;}}
    private static volatile Client client;
    public static void client(Client value){client=Objects.requireNonNull(value);}
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String n){return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("piq_sfc_home","join_"+n));}
    private static void ids(long s,int e){if(s<=0||e<=0)throw new IllegalArgumentException("Invalid session");}
    private static String text(String s){if(s==null||s.length()>192||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid text");return s;}
    public static void register(RegisterPayloadHandlersEvent e){var r=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(e,"5");
        r.playToServer(ControllerReady.TYPE,ControllerReady.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.controllerReady(s,p);}));
        r.playToServer(ControllerLeave.TYPE,ControllerLeave.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.controllerLeave(s,p);}));
        r.playToServer(Allow.TYPE,Allow.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.allowJoin(s,p);}));
        r.playToServer(Decision.TYPE,Decision.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.decideJoin(s,p);}));
        r.playToServer(Upload.TYPE,Upload.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.joinUpload(s,p.value);}));
        r.playToServer(Applied.TYPE,Applied.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.joinApplied(s,p);}));
        r.playToServer(ControllerInput.TYPE,ControllerInput.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)SfcHomeServer.joinInput(s,p);}));
        r.playToClient(Offer.TYPE,Offer.CODEC,(p,c)->dispatch(c,h->h.offer(p)));
        r.playToClient(Approval.TYPE,Approval.CODEC,(p,c)->dispatch(c,h->h.approval(p)));
        r.playToClient(Capture.TYPE,Capture.CODEC,(p,c)->dispatch(c,h->h.capture(p)));
        r.playToClient(State.TYPE,State.CODEC,(p,c)->dispatch(c,h->h.state(p)));
        r.playToClient(Result.TYPE,Result.CODEC,(p,c)->dispatch(c,h->h.result(p)));
    }
    private static void dispatch(net.neoforged.neoforge.network.handling.IPayloadContext context,java.util.function.Consumer<Client> action){
        Object source=context.connection();
        context.enqueueWork(()->{var h=client;if(h!=null&&h.acceptsConnection(source))action.accept(h);});
    }
    public record ControllerReady(UUID lease,SfcHomeNetwork.Ready ready)implements CustomPacketPayload{
        public ControllerReady{Objects.requireNonNull(lease);Objects.requireNonNull(ready);}public static final Type<ControllerReady>TYPE=id("controller_ready");
        public static final StreamCodec<RegistryFriendlyByteBuf,ControllerReady>CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);SfcHomeNetwork.Ready.CODEC.encode(b,p.ready);},b->new ControllerReady(b.readUUID(),SfcHomeNetwork.Ready.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record ControllerLeave(UUID lease,SfcHomeNetwork.Leave leave)implements CustomPacketPayload{
        public ControllerLeave{Objects.requireNonNull(lease);Objects.requireNonNull(leave);}public static final Type<ControllerLeave>TYPE=id("controller_leave");
        public static final StreamCodec<RegistryFriendlyByteBuf,ControllerLeave>CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);SfcHomeNetwork.Leave.CODEC.encode(b,p.leave);},b->new ControllerLeave(b.readUUID(),SfcHomeNetwork.Leave.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record ControllerInput(UUID lease,SfcHomeNetwork.Input input)implements CustomPacketPayload{
        public ControllerInput{Objects.requireNonNull(lease);Objects.requireNonNull(input);}public static final Type<ControllerInput>TYPE=id("controller_input");
        public static final StreamCodec<RegistryFriendlyByteBuf,ControllerInput>CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.lease);SfcHomeNetwork.Input.CODEC.encode(b,p.input);},b->new ControllerInput(b.readUUID(),SfcHomeNetwork.Input.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Offer(long session,int epoch)implements CustomPacketPayload{
        public Offer{ids(session,epoch);}public static final Type<Offer>TYPE=id("offer");
        public static final StreamCodec<RegistryFriendlyByteBuf,Offer>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);},b->new Offer(b.readVarLong(),b.readVarInt()));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Allow(long session,int epoch,boolean enabled)implements CustomPacketPayload{
        public Allow{ids(session,epoch);}public static final Type<Allow>TYPE=id("allow");
        public static final StreamCodec<RegistryFriendlyByteBuf,Allow>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeBoolean(p.enabled);},b->new Allow(b.readVarLong(),b.readVarInt(),b.readBoolean()));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Approval(long session,int epoch,UUID token,String applicant)implements CustomPacketPayload{
        public Approval{ids(session,epoch);Objects.requireNonNull(token);text(applicant);}public static final Type<Approval>TYPE=id("approval");
        public static final StreamCodec<RegistryFriendlyByteBuf,Approval>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeUtf(p.applicant,192);},b->new Approval(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readUtf(192)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Decision(long session,int epoch,UUID token,boolean accepted)implements CustomPacketPayload{
        public Decision{ids(session,epoch);Objects.requireNonNull(token);}public static final Type<Decision>TYPE=id("decision");
        public static final StreamCodec<RegistryFriendlyByteBuf,Decision>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeBoolean(p.accepted);},b->new Decision(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readBoolean()));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Capture(long session,int epoch,UUID token,int frame)implements CustomPacketPayload{
        public Capture{ids(session,epoch);Objects.requireNonNull(token);if(frame<0)throw new IllegalArgumentException("Invalid frame");}public static final Type<Capture>TYPE=id("capture");
        public static final StreamCodec<RegistryFriendlyByteBuf,Capture>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeVarInt(p.frame);},b->new Capture(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readVarInt()));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record State(long session,int epoch,UUID token,int frame,int total,int offset,String sha,byte[] data)implements CustomPacketPayload{
        public State{ids(session,epoch);Objects.requireNonNull(token);SfcHomeNetwork.hash(sha,false);if(frame<0||total<1||total>SfcJoinGate.MAX_STATE||offset<0||data==null||data.length<1||data.length>SfcJoinGate.CHUNK||(long)offset+data.length>total)throw new IllegalArgumentException("Invalid state chunk");data=data.clone();}
        @Override public byte[]data(){return data.clone();}public static final Type<State>TYPE=id("state");
        public static final StreamCodec<RegistryFriendlyByteBuf,State>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeVarInt(p.frame);b.writeVarInt(p.total);b.writeVarInt(p.offset);b.writeUtf(p.sha,64);b.writeByteArray(p.data);},b->new State(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(64),b.readByteArray(SfcJoinGate.CHUNK)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Upload(State value)implements CustomPacketPayload{
        public Upload{Objects.requireNonNull(value);}public static final Type<Upload>TYPE=id("upload");
        public static final StreamCodec<RegistryFriendlyByteBuf,Upload>CODEC=StreamCodec.of((b,p)->State.CODEC.encode(b,p.value),b->new Upload(State.CODEC.decode(b)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Applied(long session,int epoch,UUID token,int frame,String sha,boolean success)implements CustomPacketPayload{
        public Applied{ids(session,epoch);Objects.requireNonNull(token);SfcHomeNetwork.hash(sha,true);if(frame<0)throw new IllegalArgumentException("Invalid frame");}public static final Type<Applied>TYPE=id("applied");
        public static final StreamCodec<RegistryFriendlyByteBuf,Applied>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeVarInt(p.frame);b.writeUtf(p.sha,64);b.writeBoolean(p.success);},b->new Applied(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readVarInt(),b.readUtf(64),b.readBoolean()));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
    public record Result(long session,int epoch,UUID token,boolean joined,String message)implements CustomPacketPayload{
        public Result{ids(session,epoch);Objects.requireNonNull(token);text(message);}public static final Type<Result>TYPE=id("result");
        public static final StreamCodec<RegistryFriendlyByteBuf,Result>CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.session);b.writeVarInt(p.epoch);b.writeUUID(p.token);b.writeBoolean(p.joined);b.writeUtf(p.message,192);},b->new Result(b.readVarLong(),b.readVarInt(),b.readUUID(),b.readBoolean(),b.readUtf(192)));
        public Type<? extends CustomPacketPayload>type(){return TYPE;}
    }
}
