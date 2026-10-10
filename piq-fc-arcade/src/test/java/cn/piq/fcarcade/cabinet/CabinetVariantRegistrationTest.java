package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the codecs actually registered with NeoForge, including the traffic wrappers. */
@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class CabinetVariantRegistrationTest {
    private static boolean ours(ResourceLocation id){return id.getNamespace().equals("piq_fc_arcade")&&(id.getPath().startsWith("cabinet_room_")||id.getPath().startsWith("cabinet_sync_")||id.getPath().startsWith("watch_"));}
    @Test @SuppressWarnings("unchecked") void actualProductionRegistrationCarriesEveryNewContractAndRejectsTruncations()throws Exception{
        if(net.neoforged.fml.loading.LoadingModList.get()==null)net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var play=((Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null)).get(ConnectionProtocol.PLAY);
        var previous=new HashMap<ResourceLocation,PayloadRegistration<?>>();play.forEach((id,value)->{if(ours(id))previous.put(id,value);});
        play.keySet().removeIf(CabinetVariantRegistrationTest::ours);
        var providerField=WatchProviders.class.getDeclaredField("PROVIDERS");providerField.setAccessible(true);
        var providers=(Map<ResourceLocation,WatchProvider>)providerField.get(null);var oldProviders=new LinkedHashMap<>(providers);
        var registered=CabinetRooms.class.getDeclaredField("watchRegistered");registered.setAccessible(true);boolean wasRegistered=registered.getBoolean(null);
        providers.remove(CabinetRooms.WATCH_PROVIDER);registered.setBoolean(null,false);
        try{
            CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent()); // Also registers cabinet-sync.
            WatchNetwork.register(new RegisterPayloadHandlersEvent());
            var backend=ResourceLocation.parse("piq_native_arcade:mame");var dimension=ResourceLocation.parse("minecraft:overworld");
            var target=new CabinetTarget(dimension,BlockPos.ZERO,UUID.randomUUID(),true);
            var room=UUID.randomUUID();var member=UUID.randomUUID();var token=UUID.randomUUID();
            var assignment=new CabinetRoomNetwork.Assignment(room,member,member,0,2,target,backend,target,null,CabinetSyncMode.LOCAL_SYNC,false);
            var launch=new NetplayLaunchSpec("retroarch-stock-v1","ab".repeat(32),false);
            var origin=new WatchAnchor(target.anchor(),target.identity());
            var watch=new WatchNetwork.Start(1,UUID.randomUUID(),new WatchDescriptor(CabinetRooms.WATCH_PROVIDER,room,member,dimension,origin,null,List.of(origin)));
            check(play,new CabinetRoomNetwork.NetplayStart(assignment,(1L<<50)+1,token,launch),PacketFlow.CLIENTBOUND,"cabinet-room-11");
            check(play,new WatchNetwork.NetplayStart(watch,(1L<<50)+1,token,backend,"cd".repeat(32),launch),PacketFlow.CLIENTBOUND,"watch-5");
            check(play,new CabinetSyncNetwork.Mode(target,backend,-1,token,-1,-1,-1,-1,launch.variant()),PacketFlow.SERVERBOUND,"cabinet-sync-11");
            check(play,new CabinetSyncNetwork.Setting(target,backend,3,true,true,"试验",false,true,"",token,false,false,"",false,CabinetPowerSettings.DEFAULT_SECONDS,CabinetPowerSettings.DEFAULT_RENDER_DISTANCE,0,launch.variant()),PacketFlow.CLIENTBOUND,"cabinet-sync-11");
            assertThrows(UnsupportedOperationException.class,()->CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent()));
            assertThrows(UnsupportedOperationException.class,()->WatchNetwork.register(new RegisterPayloadHandlersEvent()));
        }finally{
            play.keySet().removeIf(CabinetVariantRegistrationTest::ours);play.putAll(previous);
            providers.clear();providers.putAll(oldProviders);registered.setBoolean(null,wasRegistered);
        }
    }
    @SuppressWarnings("unchecked") private static <T extends CustomPacketPayload> void check(Map<ResourceLocation,PayloadRegistration<?>> play,T payload,PacketFlow flow,String version){
        var id=payload.type().id();var registration=play.get(id);assertNotNull(registration);
        assertEquals(version,registration.version());assertFalse(registration.optional());assertEquals(flow,registration.flow().orElseThrow());
        assertEquals(List.of(ConnectionProtocol.PLAY),registration.protocols());
        var codec=(StreamCodec<RegistryFriendlyByteBuf,T>)(Object)NetworkRegistry.getCodec(id,ConnectionProtocol.PLAY,flow);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            codec.encode(buffer,payload);byte[] raw=new byte[buffer.readableBytes()];buffer.getBytes(0,raw);
            assertEquals(payload,codec.decode(buffer));assertEquals(0,buffer.readableBytes());
            for(int n=0;n<raw.length;n++){
                var truncated=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,n)),RegistryAccess.EMPTY);
                try{assertThrows(RuntimeException.class,()->codec.decode(truncated));}finally{truncated.release();}
            }
        }finally{buffer.release();}
    }
}
