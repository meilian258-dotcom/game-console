// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import cn.piq.retro.flow.DeviceSessionFlow.Stage;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Bounded common launch metadata. No paths, save owner keys, core bytes or client authority. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD)
public final class HomeLaunchNetwork {
    public static final String PROTOCOL="home-launch-1";
    public static final int SELECT=0,ALLOW=1,DENY=2,CANCEL=3,BACK=4;
    public record Row(int slot,String version,String name,String content,int players,long modified,boolean compatible){
        public Row{if(slot<1||slot>16||players<1||players>2||modified<0)throw new IllegalArgumentException("Save row");text(version,128);text(name,32);text(content,128);}
        public boolean occupied(){return !version.isEmpty();}
    }
    public record Choice(int slot,String version,String name,int savePlayers,boolean resume){
        public Choice{if(slot<1||slot>16||savePlayers<1||savePlayers>2)throw new IllegalArgumentException("Save choice");text(version,128);text(name,32);}
    }
    public record View(UUID token,long revision,ResourceLocation system,String label,String title,int mode,int maxPlayers,
                       Stage stage,List<Row> rows,String message) implements CustomPacketPayload {
        public View{Objects.requireNonNull(token);Objects.requireNonNull(system);Objects.requireNonNull(stage);text(system.toString(),128);text(label,64);text(title,128);text(message,240);
            if(revision<1||mode<0||mode>2||maxPlayers<1||maxPlayers>2)throw new IllegalArgumentException("Launch view");rows=List.copyOf(rows);if(rows.size()>16||mode==0&&!rows.isEmpty())throw new IllegalArgumentException("Launch rows");var slots=new HashSet<Integer>();for(var row:rows)if(!slots.add(row.slot())||row.players()>maxPlayers)throw new IllegalArgumentException("Launch row ports/slot");}
        public static final Type<View> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_launch_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf,View> CODEC=StreamCodec.of((b,v)->{
            b.writeUUID(v.token);b.writeVarLong(v.revision);b.writeUtf(v.system.toString(),128);b.writeUtf(v.label,64);b.writeUtf(v.title,128);b.writeByte(v.mode);b.writeByte(v.maxPlayers);b.writeByte(v.stage.ordinal());b.writeVarInt(v.rows.size());
            for(var r:v.rows){b.writeByte(r.slot);b.writeUtf(r.version,128);b.writeUtf(r.name,32);b.writeUtf(r.content,128);b.writeByte(r.players);b.writeVarLong(r.modified);b.writeBoolean(r.compatible);}b.writeUtf(v.message,240);
        },b->{var id=b.readUUID();long rev=b.readVarLong();var sys=ResourceLocation.parse(b.readUtf(128));String label=b.readUtf(64),title=b.readUtf(128);int mode=b.readUnsignedByte(),max=b.readUnsignedByte(),stage=b.readUnsignedByte(),count=b.readVarInt();if(stage>=Stage.values().length||count<0||count>16)throw new IllegalArgumentException("Launch bounds");var rows=new ArrayList<Row>();for(int i=0;i<count;i++)rows.add(new Row(b.readUnsignedByte(),b.readUtf(128),b.readUtf(32),b.readUtf(128),b.readUnsignedByte(),b.readVarLong(),b.readBoolean()));return new View(id,rev,sys,label,title,mode,max,Stage.values()[stage],rows,b.readUtf(240));});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Action(UUID token,long revision,int operation,Choice choice) implements CustomPacketPayload {
        public Action{Objects.requireNonNull(token);if(revision<1||operation<SELECT||operation>BACK||operation==SELECT!=(choice!=null))throw new IllegalArgumentException("Launch action");}
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("piq_fc_arcade","home_launch_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,a)->{b.writeUUID(a.token);b.writeVarLong(a.revision);b.writeByte(a.operation);if(a.choice!=null){b.writeByte(a.choice.slot);b.writeUtf(a.choice.version,128);b.writeUtf(a.choice.name,32);b.writeByte(a.choice.savePlayers);b.writeBoolean(a.choice.resume);}},b->{var token=b.readUUID();long rev=b.readVarLong();int op=b.readUnsignedByte();return new Action(token,rev,op,op==SELECT?new Choice(b.readUnsignedByte(),b.readUtf(128),b.readUtf(32),b.readUnsignedByte(),b.readBoolean()):null);});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void text(String value,int max){if(value==null||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Launch text");}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){var r=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,PROTOCOL);
        r.playToServer(Action.TYPE,Action.CODEC,(a,c)->c.enqueueWork(()->{if(c.player() instanceof net.minecraft.server.level.ServerPlayer p&&p.connection.getConnection()==c.connection())HomeLaunchServer.action(p,a);}));
        r.playToClient(View.TYPE,View.CODEC,(v,c)->c.enqueueWork(()->{if(net.neoforged.fml.loading.FMLEnvironment.dist.isClient())HomeLaunchScreen.receive(c.connection(),v);}));
    }
    static void send(net.minecraft.server.level.ServerPlayer p,View v){PacketDistributor.sendToPlayer(p,v);}
    static void send(Action a){PacketDistributor.sendToServer(a);}
    private HomeLaunchNetwork(){}
}
