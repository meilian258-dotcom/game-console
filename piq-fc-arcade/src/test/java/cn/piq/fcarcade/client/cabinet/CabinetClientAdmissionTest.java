package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetClientAdmissionTest {
    static ResourceLocation backend(int network,int snapshot,int netplay) {
        var id=ResourceLocation.parse("admission_test:b"+UUID.randomUUID().toString().replace("-",""));
        CabinetBackends.register(id,"Admission test",false);
        CabinetBackends.registerNetwork(id,network);
        if(snapshot>0)CabinetBackends.registerSnapshotSync(id,snapshot,"test-profile");
        if(netplay>0)CabinetNetplay.register(id,netplay);
        return id;
    }
    static CabinetRoomNetwork.Assignment assignment(ResourceLocation id,int capacity,int port,CabinetSyncMode mode) {
        var dimension=ResourceLocation.parse("minecraft:overworld");
        var a=new CabinetTarget(dimension,BlockPos.ZERO,UUID.randomUUID(),true);
        var b=capacity>2?new CabinetTarget(dimension,new BlockPos(3,0,0),UUID.randomUUID(),capacity==4):null;
        var host=UUID.randomUUID();
        return new CabinetRoomNetwork.Assignment(UUID.randomUUID(),port==0?host:UUID.randomUUID(),host,port,capacity,port<2?a:b,id,a,b,mode);
    }
    static CabinetRoomNetwork.NetplayStart grant(CabinetRoomNetwork.Assignment a) {
        return new CabinetRoomNetwork.NetplayStart(a,1L<<50,UUID.randomUUID());
    }
    @Test void fourPortNetplayDoesNotInheritTwoPortSnapshotLimit() {
        var id=backend(4,2,4);
        for(int capacity:new int[]{2,3,4})for(int port=0;port<capacity;port++){
            var a=assignment(id,capacity,port,CabinetSyncMode.LOCAL_SYNC);
            assertTrue(CabinetClientAdmission.accepts(a,grant(a)),"capacity="+capacity+" port="+port);
            assertEquals(capacity<=2,CabinetClientAdmission.accepts(a,null));
        }
    }
    @Test void realDecodedGrantUsesDecodedAssignmentIdentity() {
        var id=backend(4,2,4);
        for(int port=0;port<4;port++){
            var original=grant(assignment(id,4,port,CabinetSyncMode.LOCAL_SYNC));
            var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try{
                CabinetRoomNetwork.NetplayStart.CODEC.encode(buf,original);
                var decoded=CabinetRoomNetwork.NetplayStart.CODEC.decode(buf);
                assertTrue(CabinetClientAdmission.accepts(decoded.assignment(),decoded));
                assertFalse(CabinetClientAdmission.accepts(decoded.assignment(),original));
            }finally{buf.release();}
        }
    }
    @Test void unregisteredOrTwoPortNetplayCannotBorrowMediaOrSnapshotCapacity() {
        for(int netplay:new int[]{0,2}){
            var a=assignment(backend(4,4,netplay),4,3,CabinetSyncMode.LOCAL_SYNC);
            assertFalse(CabinetClientAdmission.accepts(a,grant(a)));
            assertTrue(CabinetClientAdmission.accepts(a,null));
        }
        var a=assignment(backend(2,2,4),4,0,CabinetSyncMode.LOCAL_SYNC);
        assertFalse(CabinetClientAdmission.accepts(a,grant(a)));
    }
    @Test void standaloneNetplayCapabilityDoesNotRequireSnapshotAdapter() {
        var id=backend(4,0,4);
        var a=assignment(id,4,2,CabinetSyncMode.LOCAL_SYNC);
        assertTrue(CabinetClientAdmission.accepts(a,grant(a)));
        assertFalse(CabinetClientAdmission.accepts(a,null));
        for(var mode:List.of(CabinetSyncMode.MEDIA,CabinetSyncMode.SERVER_MEDIA))
            assertTrue(CabinetClientAdmission.accepts(assignment(id,4,3,mode),null));
    }
    @Test void productionChecksBeforeAcquiringAndKeepsBothScreensAndTransportDispatch() throws Exception {
        String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java"));
        String admission=s.substring(s.indexOf("@Override public void assignment("),s.indexOf("@Override public void seat("));
        assertTrue(admission.indexOf("!CabinetClientAdmission.accepts(request,netplayGrant)")<admission.indexOf("room=request"));
        assertFalse(admission.contains("syncMaxPlayers("));
        assertTrue(admission.contains("(!netplay()&&!CabinetBackends.supportsSync(request.backend()))"));
        assertTrue(s.indexOf("if(netplay()){startNetplay(chosen,remember);return;}")<s.indexOf("if(synchronous()){startSynchronous(chosen,remember);return;}"));
        assertTrue(s.contains("CabinetVideoDisplay.render(event,room.secondary(),textureId,aspect,rotation)"));
        assertTrue(s.contains("netplayGrant=value;assignment(value.assignment())"));
    }
}
