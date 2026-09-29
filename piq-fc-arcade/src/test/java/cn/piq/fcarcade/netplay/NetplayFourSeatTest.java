package cn.piq.fcarcade.netplay;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetplayFourSeatTest {
    @Test void fourSeatActualGameCodecRoundTripsAndRejectsTruncation(){
        var dim=net.minecraft.resources.ResourceLocation.parse("minecraft:overworld");
        var a=new cn.piq.fcarcade.cabinet.CabinetTarget(dim,net.minecraft.core.BlockPos.ZERO,UUID.randomUUID(),true);
        var b=new cn.piq.fcarcade.cabinet.CabinetTarget(dim,new net.minecraft.core.BlockPos(4,0,0),UUID.randomUUID(),true);
        var host=UUID.randomUUID();
        for(int port=0;port<4;port++){
            var assignment=new cn.piq.fcarcade.cabinet.CabinetRoomNetwork.Assignment(UUID.randomUUID(),port==0?host:UUID.randomUUID(),host,port,4,port<2?a:b,
                net.minecraft.resources.ResourceLocation.parse("piq_native_arcade:mame"),a,b,cn.piq.fcarcade.cabinet.CabinetSyncMode.LOCAL_SYNC);
            var start=new cn.piq.fcarcade.cabinet.CabinetRoomNetwork.NetplayStart(assignment,1L<<50,UUID.randomUUID());
            var codec=cn.piq.fcarcade.cabinet.CabinetRoomNetwork.NetplayStart.CODEC;
            var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try{codec.encode(buf,start);byte[] raw=new byte[buf.readableBytes()];buf.getBytes(0,raw);assertEquals(start,codec.decode(buf));
                for(int n=0;n<raw.length;n++){var cut=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(Arrays.copyOf(raw,n)),net.minecraft.core.RegistryAccess.EMPTY);
                    try{assertThrows(RuntimeException.class,()->codec.decode(cut));}finally{cut.release();}}
            }finally{buf.release();}
        }
        for(int port:new int[]{-2,4,255})assertThrows(IllegalArgumentException.class,()->new NetplayNetwork.Data(new NetplayChunk(1,UUID.randomUUID(),0,0,new byte[0]),port));
    }
    @Test void fixedPortsSurviveOutOfOrderJoinRevokeAndStaleTickets(){
        Object host=new Object(),p2=new Object(),p3=new Object(),p4=new Object(),other=new Object();
        var messages=new ArrayList<NetplayRelay.Delivery>();
        var relay=new NetplayRelay<Object>(7,host,(who,d)->messages.add(d),4);
        var t3=relay.grant(p3,2);var t4=relay.grant(p4,3);var t2=relay.grant(p2,1);
        assertNull(relay.grant(other,3));assertThrows(IllegalArgumentException.class,()->relay.grant(other,0));
        for(var t:List.of(t3,t4,t2)){Object who=t==t3?p3:t==t4?p4:p2;relay.receive(who,new NetplayChunk(7,t.id(),0,0,new byte[0]));assertEquals(t.port(),messages.getLast().port());}
        relay.revoke(p3);var fresh=relay.grant(p3,2);assertNotEquals(t3.id(),fresh.id());messages.clear();
        relay.receive(p3,new NetplayChunk(7,t3.id(),0,0,new byte[0]));assertTrue(messages.isEmpty());
        assertEquals(t4,relay.grant(p4,3));assertEquals(t2,relay.grant(p2,1));
        relay.receive(p3,new NetplayChunk(7,fresh.id(),0,0,new byte[0]));assertEquals(2,messages.getLast().port());
    }
    @Test void fourSeatObserversReserveAllThreeRemoteSeats(){
        var host=new Object();var r=new NetplayRelay<Object>(1,host,(p,d)->{},4);
        for(int i=0;i<5;i++)assertNotNull(r.grantObserver(new Object()));
        assertNull(r.grantObserver(new Object()));for(int port=1;port<4;port++)assertNotNull(r.grant(new Object(),port));
    }
    @Test void explicitConfigsAndProfileBounds(){
        for(int port=0;port<4;port++){
            var grant=new NetplayProcess.Grant(1,UUID.randomUUID(),port==0,true,port);
            String cfg=NetplayProcess.config(grant,false);
            assertTrue(cfg.contains("netplay_request_device_p"+(port+1)+" = \"true\""));
            for(int other=0;other<4;other++)if(other!=port)assertFalse(cfg.contains("netplay_request_device_p"+(other+1)+" = \"true\""));
        }
        assertEquals(2,NetplayProfile.fc().ports());assertThrows(IllegalArgumentException.class,()->NetplayProfile.fc().deviceForPort(2));
        assertThrows(IllegalArgumentException.class,()->new NetplayProcess.Grant(1,UUID.randomUUID(),false,true,0));
        assertThrows(IllegalArgumentException.class,()->new NetplayProcess.Grant(1,UUID.randomUUID(),false,false,2));
    }
    @Test void fourSeatWiringKeepsLegacyTwoPlayerSnapshotPolicy()throws Exception{
        String root="src/main/java/cn/piq/fcarcade/";
        assertTrue(Files.readString(Path.of(root+"cabinet/CabinetRooms.java")).contains("netplay?CabinetNetplay.maxPlayers(backend):CabinetBackends.syncMaxPlayers(backend)"));
        assertTrue(Files.readString(Path.of(root+"cabinet/CabinetNetplay.java")).contains("member.port!=a.port()"));
        assertTrue(Files.readString(Path.of(root+"client/cabinet/CabinetNetplayEmulator.java")).contains("port==0,true,port"));
        assertTrue(Files.readString(Path.of(root+"netplay/NetplayNetwork.java")).contains("netplay-5"));
    }
}
