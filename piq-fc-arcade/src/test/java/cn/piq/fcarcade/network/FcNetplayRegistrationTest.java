package cn.piq.fcarcade.network;

import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.netplay.*;
import cn.piq.fcarcade.session.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class FcNetplayRegistrationTest {
    @Test @SuppressWarnings("unchecked") void versionSixRegistersMandatoryStateGunAndBidirectionalTransport() throws Exception {
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var all=(Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null);
        var play=all.get(ConnectionProtocol.PLAY);
        var ids=List.of(NetplayNetwork.State.TYPE.id(),NetplayNetwork.GunFrame.TYPE.id(),NetplayNetwork.Data.TYPE.id());
        var old=new HashMap<ResourceLocation,PayloadRegistration<?>>();for(var id:ids)old.put(id,play.remove(id));int size=play.size();
        try {
            NetplayNetwork.register(new RegisterPayloadHandlersEvent());assertEquals(size+3,play.size());
            for(var id:ids){assertEquals("netplay-6",play.get(id).version());assertFalse(play.get(id).optional());}
            assertEquals(PacketFlow.CLIENTBOUND,play.get(ids.get(0)).flow().orElseThrow());
            assertEquals(PacketFlow.CLIENTBOUND,play.get(ids.get(1)).flow().orElseThrow());
            assertTrue(play.get(ids.get(2)).flow().isEmpty());
            var payload=new ArcadeSessionPayload(BlockPos.ZERO,7,ArcadeMode.LOCKSTEP,ArcadeRole.SPECTATOR,1,"",16,16,100,
                    "a".repeat(64),1,false,true,NesCoreVariant.LIBRETRO_V1,true,true,null,1,false);
            for(boolean jni:new boolean[]{false,true}) {
                var state=new NetplayNetwork.State(payload,UUID.randomUUID(),true,jni);
                roundTrip((StreamCodec<RegistryFriendlyByteBuf,NetplayNetwork.State>)(Object)NetworkRegistry.getCodec(ids.get(0),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND),state);
            }
            var gun=new NetplayNetwork.GunFrame(7,UUID.randomUUID(),1,2,3,0,0);
            roundTrip((StreamCodec<RegistryFriendlyByteBuf,NetplayNetwork.GunFrame>)(Object)NetworkRegistry.getCodec(ids.get(1),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND),gun);
            assertThrows(UnsupportedOperationException.class,()->NetplayNetwork.register(new RegisterPayloadHandlersEvent()));
        } finally {for(var id:ids){play.remove(id);if(old.get(id)!=null)play.put(id,old.get(id));}}
    }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T expected) {
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(buf,expected);assertEquals(expected,codec.decode(buf));assertEquals(0,buf.readableBytes());}finally{buf.release();}
    }
}
