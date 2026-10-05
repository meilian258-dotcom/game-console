// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.network;

import cn.piq.fcarcade.home.flow.HomeLaunchNetwork;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.codec.StreamCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class HomeLaunchRegistrationTest {
    @Test @SuppressWarnings("unchecked") void productionRegistersVersionTwoWithExactDirectionsAndCodecs() throws Exception {
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var all=(Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null);
        var play=all.get(ConnectionProtocol.PLAY);
        var a=HomeLaunchNetwork.Action.TYPE.id();var v=HomeLaunchNetwork.View.TYPE.id();
        var oldA=play.remove(a);var oldV=play.remove(v);int size=play.size();
        try {
            HomeLaunchNetwork.register(new RegisterPayloadHandlersEvent());
            assertEquals(size+2,play.size());
            assertEquals("home-launch-2",play.get(a).version());assertEquals("home-launch-2",play.get(v).version());
            assertFalse(play.get(a).optional());assertFalse(play.get(v).optional());
            assertEquals(PacketFlow.SERVERBOUND,play.get(a).flow().orElseThrow());
            assertEquals(PacketFlow.CLIENTBOUND,play.get(v).flow().orElseThrow());
            // TrafficPayloadRegistrar may wrap the codec. Exercise the real registered
            // wire path, not object identity of the inner codec.
            var action=new HomeLaunchNetwork.Action(UUID.randomUUID(),2,HomeLaunchNetwork.CANCEL,null);
            var view=new HomeLaunchNetwork.View(UUID.randomUUID(),3,ResourceLocation.parse("piq_sfc_home:sfc"),"SFC","游戏",1,2,
                cn.piq.retro.flow.DeviceSessionFlow.Stage.SAVE_SELECTION,List.of(new HomeLaunchNetwork.Row(1,"v1","原有进度","abc",1,0,true,false)),"选择存档");
            roundTrip((StreamCodec<RegistryFriendlyByteBuf,HomeLaunchNetwork.Action>)(Object)NetworkRegistry.getCodec(a,ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND),action);
            roundTrip((StreamCodec<RegistryFriendlyByteBuf,HomeLaunchNetwork.View>)(Object)NetworkRegistry.getCodec(v,ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND),view);
            assertThrows(UnsupportedOperationException.class,()->HomeLaunchNetwork.register(new RegisterPayloadHandlersEvent()));
        } finally {
            play.remove(a);play.remove(v);if(oldA!=null)play.put(a,oldA);if(oldV!=null)play.put(v,oldV);
        }
    }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(buffer,value);assertEquals(value,codec.decode(buffer));assertEquals(0,buffer.readableBytes());}
        finally{buffer.release();}
    }
}
