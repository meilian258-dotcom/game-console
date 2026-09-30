import cn.piq.gba.GbaMod;
import cn.piq.gba.item.*;
import cn.piq.fcarcade.home.content.*;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.*;
import java.util.*;

/** Real final-JAR item registry/components and payload codecs. Not a player/world test. */
public class GbaCartridge13Probe {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void bad(Runnable r){try{r.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        var output=System.out;
        check(Path.of(GbaMod.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"final GBA origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var bus=net.neoforged.bus.api.BusBuilder.builder().build();new GbaMod(bus);
        var constructor=net.neoforged.neoforge.registries.RegisterEvent.class.getDeclaredConstructor(net.minecraft.resources.ResourceKey.class,Registry.class);constructor.setAccessible(true);
        var registry=BuiltInRegistries.ITEM;((MappedRegistry<?>)registry).unfreeze();bus.post(constructor.newInstance(registry.key(),registry));registry.freeze();
        check(registry.getKey(GbaMod.CARTRIDGE.get()).toString().equals("piq_gba:gba_cartridge"),"real registered cartridge");
        check(!((Item)GbaMod.HANDHELD.get() instanceof BlockItem),"handheld remains item");
        check(ContentCards.adapter(GbaMod.BACKEND).maxBytes()==32*1024*1024,"32 MiB GBA adapter");
        var lookup=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for(var hand:InteractionHand.values()){
            ItemStack machine=new ItemStack(GbaMod.HANDHELD.get()),card=new ItemStack(GbaMod.CARTRIDGE.get());
            check(machine.getMaxStackSize()==1&&card.getMaxStackSize()==1,"nonstacking machine and card");
            check(GbaCartridgeSlot.valid(machine)&&GbaCartridgeSlot.card(machine).isEmpty(),"legacy empty machine");
            card.set(DataComponents.CUSTOM_NAME,Component.literal("个人卡带"));
            var entry=new ContentCardStore.Entry("a".repeat(64),"测试.gba",32*1024*1024);
            ContentCardData.write(card,GbaMod.BACKEND,entry,"测试游戏");
            GbaCartridgeSlot.set(machine,card);UUID id=GbaCartridgeSlot.id(machine);
            check(!id.equals(GbaCartridgeSlot.EMPTY_ID),"persistent machine ID");
            check(ItemStack.matches(card,GbaCartridgeSlot.card(machine)),"full real card metadata retained");
            card.set(DataComponents.CUSTOM_NAME,Component.literal("changed outside"));
            check(!ItemStack.matches(card,GbaCartridgeSlot.card(machine)),"slot does not alias outside card");
            var restored=ItemStack.parse(lookup,machine.save(lookup)).orElseThrow();
            check(ItemStack.matches(machine,restored)&&id.equals(GbaCartridgeSlot.id(restored)),"NBT persists inserted card and device");
            var returned=GbaCartridgeSlot.card(restored);GbaCartridgeSlot.set(restored,ItemStack.EMPTY);
            check(GbaCartridgeSlot.card(restored).isEmpty()&&ContentCardData.read(returned,GbaMod.BACKEND).equals(entry),"eject returns content identity");
            check(id.equals(GbaCartridgeSlot.id(restored)),"eject never changes machine identity");
            bad(()->GbaCartridgeSlot.set(machine,new ItemStack(Items.DIRT)));
            bad(()->GbaCartridgeSlot.set(machine,new ItemStack(GbaMod.CARTRIDGE.get(),2)));
            machine.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(returned,returned.copy())));
            check(!GbaCartridgeSlot.valid(machine)&&GbaCartridgeSlot.card(machine).isEmpty(),"reject malformed two-slot data");
            for(int action=0;action<=GbaHandheldNetwork.OFF;action++){
                var request=new GbaHandheldNetwork.Request(action,hand,id,UUID.randomUUID());
                var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),lookup);
                try{GbaHandheldNetwork.Request.CODEC.encode(b,request);check(b.readableBytes()<=40,"bounded request bytes");check(request.equals(GbaHandheldNetwork.Request.CODEC.decode(b))&&b.readableBytes()==0,"real request codec");}finally{b.release();}
            }
        }
        bad(()->new GbaHandheldNetwork.Request(4,InteractionHand.MAIN_HAND,UUID.randomUUID(),UUID.randomUUID()));
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),lookup);
        try{b.writeByte(255);b.writeEnum(InteractionHand.MAIN_HAND);b.writeUUID(UUID.randomUUID());b.writeUUID(UUID.randomUUID());bad(()->GbaHandheldNetwork.Request.CODEC.decode(b));}finally{b.release();}
        GbaHandheldNetwork.register(new RegisterPayloadHandlersEvent());
        // Directional traffic metering wraps codecs, so test actual wire behavior, not object identity.
        var serverCodec=(net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf,GbaHandheldNetwork.Request>)(Object)NetworkRegistry.getCodec(GbaHandheldNetwork.Request.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND);
        var clientCodec=(net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf,GbaHandheldNetwork.Reply>)(Object)NetworkRegistry.getCodec(GbaHandheldNetwork.Reply.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND);
        check(serverCodec!=null&&clientCodec!=null,"actual distinct directional registrations");
        var wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),lookup);
        try{
            var req=new GbaHandheldNetwork.Request(GbaHandheldNetwork.POWER,InteractionHand.OFF_HAND,UUID.randomUUID(),UUID.randomUUID());
            serverCodec.encode(wire,req);check(req.equals(serverCodec.decode(wire))&&wire.readableBytes()==0,"registered request codec roundtrip");
            var reply=new GbaHandheldNetwork.Reply(req.nonce(),UUID.randomUUID());clientCodec.encode(wire,reply);
            check(reply.equals(clientCodec.decode(wire))&&wire.readableBytes()==0,"registered reply codec roundtrip");
        }finally{wire.release();}
        try{GbaHandheldNetwork.register(new RegisterPayloadHandlersEvent());throw new AssertionError("duplicate accepted");}catch(UnsupportedOperationException expected){checks++;}
        byte[] good=new byte[192];good[0xb2]=(byte)0x96;GbaCardRom.validate(good);checks++;
        for(byte[] invalid:List.of(new byte[191],new byte[192])){try{GbaCardRom.validate(invalid);throw new AssertionError("bad ROM accepted");}catch(java.io.IOException expected){checks++;}}
        output.println("PASS GbaCartridge13Probe checks="+checks+" final-jar-only; no Minecraft player interaction");
    }
}
