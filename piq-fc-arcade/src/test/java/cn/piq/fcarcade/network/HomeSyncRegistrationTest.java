package cn.piq.fcarcade.network;

import cn.piq.fcarcade.home.HomeSyncNetwork;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
class HomeSyncRegistrationTest {
    @Test @SuppressWarnings("unchecked") void versionSixRegistersExactlyTwoMandatoryDirectionsAndRealCodecs() throws Exception {
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var all=(Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null);
        var play=all.get(ConnectionProtocol.PLAY);var requestId=HomeSyncNetwork.Request.TYPE.id();var settingId=HomeSyncNetwork.Setting.TYPE.id();
        var oldRequest=play.remove(requestId);var oldSetting=play.remove(settingId);int size=play.size();
        try{
            HomeSyncNetwork.register(new RegisterPayloadHandlersEvent());assertEquals(size+2,play.size());
            for(var id:List.of(requestId,settingId)){assertEquals("home-sync-6",play.get(id).version());assertFalse(play.get(id).optional());}
            assertEquals(PacketFlow.SERVERBOUND,play.get(requestId).flow().orElseThrow());
            assertEquals(PacketFlow.CLIENTBOUND,play.get(settingId).flow().orElseThrow());
            var request=new HomeSyncNetwork.Request(UUID.randomUUID(),3,-1,-1,-1,0);
            var setting=new HomeSyncNetwork.Setting(UUID.randomUUID(),4,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),"MD",4,17,true,"2P 许可已保存",false,true,true,true,true,Collections.nCopies(5,""),false,true);
            roundTrip((StreamCodec<RegistryFriendlyByteBuf,HomeSyncNetwork.Request>)(Object)NetworkRegistry.getCodec(requestId,ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND),request);
            roundTrip((StreamCodec<RegistryFriendlyByteBuf,HomeSyncNetwork.Setting>)(Object)NetworkRegistry.getCodec(settingId,ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND),setting);
            assertThrows(UnsupportedOperationException.class,()->HomeSyncNetwork.register(new RegisterPayloadHandlersEvent()));
        }finally{play.remove(requestId);play.remove(settingId);if(oldRequest!=null)play.put(requestId,oldRequest);if(oldSetting!=null)play.put(settingId,oldSetting);}
    }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T expected){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(b,expected);assertEquals(expected,codec.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}
    }
}
