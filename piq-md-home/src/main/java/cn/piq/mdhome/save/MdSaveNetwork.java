// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.save;

import cn.piq.mdhome.MdMod;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Only selection metadata. Checkpoint bytes stay on the shared bounded save lane. */
@EventBusSubscriber(modid=MdMod.ID,bus=EventBusSubscriber.Bus.MOD)
public final class MdSaveNetwork {
    private static Consumer<Selection> receiver=r->{};
    public static void receiver(Consumer<Selection> handler){receiver=Objects.requireNonNull(handler);}
    public record Slot(int slot,String version,String name,String rom,int players,long modified,boolean compatible){
        public Slot{if(slot<1||slot>3||version==null||!version.isEmpty()&&!version.matches("[0-9a-f]{64}")||rom==null||!rom.isEmpty()&&!rom.matches("[0-9a-f]{64}")||players<1||players>2||modified<0)throw new IllegalArgumentException("存档槽数据无效");text(name,32);}
        public boolean occupied(){return !version.isEmpty();}
    }
    public record Selection(UUID token,String rom,String title,int mode,int maxPlayers,List<Slot> slots,String message,boolean closed)implements CustomPacketPayload{
        public Selection{Objects.requireNonNull(token);if(rom==null||!rom.matches("[0-9a-f]{64}")||mode<0||mode>2||maxPlayers<1||maxPlayers>2)throw new IllegalArgumentException("存档选择数据无效");text(title,128);text(message,240);slots=List.copyOf(slots);if(slots.size()!=(mode==0?0:mode==1?1:3))throw new IllegalArgumentException("存档槽数量无效");for(int i=0;i<slots.size();i++)if(slots.get(i).slot()!=i+1)throw new IllegalArgumentException("存档槽顺序无效");}
        public static final Type<Selection> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MdMod.ID,"save_selection"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Selection> CODEC=StreamCodec.of((b,r)->{b.writeUUID(r.token);b.writeUtf(r.rom,64);b.writeUtf(r.title,128);b.writeByte(r.mode);b.writeByte(r.maxPlayers);b.writeVarInt(r.slots.size());for(var s:r.slots){b.writeByte(s.slot);b.writeUtf(s.version,64);b.writeUtf(s.name,32);b.writeUtf(s.rom,64);b.writeByte(s.players);b.writeVarLong(s.modified);b.writeBoolean(s.compatible);}b.writeUtf(r.message,240);b.writeBoolean(r.closed);},b->{UUID t=b.readUUID();String rom=b.readUtf(64),title=b.readUtf(128);int mode=b.readUnsignedByte(),max=b.readUnsignedByte(),n=b.readVarInt();if(n<0||n>3)throw new IllegalArgumentException("存档槽数量无效");var rows=new ArrayList<Slot>();for(int i=0;i<n;i++)rows.add(new Slot(b.readUnsignedByte(),b.readUtf(64),b.readUtf(32),b.readUtf(64),b.readUnsignedByte(),b.readVarLong(),b.readBoolean()));return new Selection(t,rom,title,mode,max,rows,b.readUtf(240),b.readBoolean());});
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Action(UUID token,int slot,String version,String name,int players,boolean resume,boolean cancel)implements CustomPacketPayload{
        public Action{Objects.requireNonNull(token);if(slot<1||slot>3||version==null||!version.isEmpty()&&!version.matches("[0-9a-f]{64}")||players<1||players>2)throw new IllegalArgumentException("存档选择无效");text(name,32);}
        public static final Type<Action> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MdMod.ID,"save_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Action> CODEC=StreamCodec.of((b,r)->{b.writeUUID(r.token);b.writeByte(r.slot);b.writeUtf(r.version,64);b.writeUtf(r.name,32);b.writeByte(r.players);b.writeBoolean(r.resume);b.writeBoolean(r.cancel);},b->new Action(b.readUUID(),b.readUnsignedByte(),b.readUtf(64),b.readUtf(32),b.readUnsignedByte(),b.readBoolean(),b.readBoolean()));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static void text(String s,int n){if(s==null||s.length()>n||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("存档文本无效");}
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event){
        var r=cn.piq.fcarcade.network.TrafficPayloadRegistrar.create(event,"md-public-save-1");
        r.playToServer(Action.TYPE,Action.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof net.minecraft.server.level.ServerPlayer player)MdPublicSaves.action(player,p);}));
        r.playToClient(Selection.TYPE,Selection.CODEC,(p,c)->c.enqueueWork(()->{if(net.neoforged.fml.loading.FMLEnvironment.dist.isClient())Client.receive(c.connection(),p);}));
    }
    private static final class Client{static void receive(net.minecraft.network.Connection c,Selection s){var mc=net.minecraft.client.Minecraft.getInstance();if(mc.getConnection()!=null&&mc.getConnection().getConnection()==c)receiver.accept(s);}}
    public static void send(Action r){PacketDistributor.sendToServer(r);}
    public static void send(net.minecraft.server.level.ServerPlayer p,Selection r){PacketDistributor.sendToPlayer(p,r);}
    private MdSaveNetwork(){}
}
