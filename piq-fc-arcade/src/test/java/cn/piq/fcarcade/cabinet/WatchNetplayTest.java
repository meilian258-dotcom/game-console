package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.nio.file.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchNetplayTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static WatchNetwork.Start watch(){return new WatchNetwork.Start(1,id(1),new WatchDescriptor(
        ResourceLocation.parse("piq_fc_arcade:cabinet"),id(2),id(3),ResourceLocation.parse("minecraft:overworld"),
        new WatchAnchor(BlockPos.ZERO,id(4)),null,List.of(new WatchAnchor(BlockPos.ZERO,id(4)))));}
    @Test void actualObserverCodecRoundTripsAndRejectsEveryTruncatedPayload(){
        var grant=new WatchNetwork.NetplayStart(watch(),999,id(5),ResourceLocation.parse("piq_native_arcade:mame"),"ab".repeat(32));
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{WatchNetwork.NetplayStart.CODEC.encode(b,grant);byte[] raw=new byte[b.readableBytes()];b.getBytes(0,raw);
            assertEquals(grant,WatchNetwork.NetplayStart.CODEC.decode(b));assertEquals(0,b.readableBytes());
            for(int n=0;n<raw.length;n++){var shortBuf=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(raw,n)),RegistryAccess.EMPTY);
                try{assertThrows(RuntimeException.class,()->WatchNetwork.NetplayStart.CODEC.decode(shortBuf));}finally{shortBuf.release();}}
        }finally{b.release();}
    }
    @Test void grantsRejectMalformedHashesAndIds(){
        assertThrows(IllegalArgumentException.class,()->new WatchNetwork.NetplayStart(watch(),-1,id(5),ResourceLocation.parse("a:b"),"a".repeat(64)));
        for(String bad:List.of("", "../game", "a".repeat(63),"A".repeat(64)))assertThrows(IllegalArgumentException.class,()->new WatchNetwork.NetplayStart(watch(),0,id(5),ResourceLocation.parse("a:b"),bad));
    }
    @Test void spectatorCapacityAlwaysLeavesRoomForSecondPlayer(){
        Object host=new Object(),p2=new Object();var room=new NetplayRelay<Object>(1,host,(p,d)->{});
        for(int n=0;n<7;n++)assertFalse(room.grantObserver(new Object()).player());
        assertNull(room.grantObserver(new Object()));assertTrue(room.grant(p2,true).player());
        assertNull(room.grantObserver(new Object()));
    }
    @Test void retiringObserverCannotRevokeNewControllerTicket(){
        Object host=new Object(),viewer=new Object();var messages=new ArrayList<NetplayRelay.Delivery>();
        var room=new NetplayRelay<Object>(1,host,(p,d)->messages.add(d));var old=room.grantObserver(viewer);var player=room.grant(viewer,true);
        messages.clear();room.revoke(viewer,old.id());room.receive(viewer,new NetplayChunk(1,player.id(),NetplayChunk.OPEN,0,new byte[0]));
        assertEquals(1,messages.size());assertTrue(messages.getFirst().player());
    }
    @Test void returningControllerCanBecomeReadonlyWithFreshTicket(){
        Object host=new Object(),viewer=new Object();var messages=new ArrayList<NetplayRelay.Delivery>();
        var room=new NetplayRelay<Object>(1,host,(p,d)->messages.add(d));var old=room.grant(viewer,true);room.revoke(viewer,old.id());
        var observer=room.grantObserver(viewer);assertNotEquals(old.id(),observer.id());messages.clear();
        room.receive(viewer,new NetplayChunk(1,old.id(),NetplayChunk.OPEN,0,new byte[0]));assertTrue(messages.isEmpty());
        room.receive(viewer,new NetplayChunk(1,observer.id(),NetplayChunk.OPEN,0,new byte[0]));assertFalse(messages.getFirst().player());
    }
    @Test void watchLeaseExpiresOrLeavesRangeBeforeNewAdmission(){
        var ledger=new WatchLedger();var c=new Object();var source=new WatchLedger.Source(id(2),id(3));
        var lease=ledger.select(id(8),c,List.of(new WatchLedger.Candidate(source,0)),true,0);
        assertNotNull(ledger.authorized(id(8),c,lease.token(),lease.revision(),0));
        assertNull(ledger.select(id(8),c,List.of(new WatchLedger.Candidate(source,10000)),true,1));
        assertNull(ledger.authorized(id(8),c,lease.token(),lease.revision(),1));
        var next=ledger.select(id(8),c,List.of(new WatchLedger.Candidate(source,0)),true,2);
        assertNotEquals(lease.token(),next.token());assertNull(ledger.authorized(id(8),new Object(),next.token(),next.revision(),3));
    }
    @Test void spectatorInputAlwaysRemainsNeutral(){
        var process=new NetplayProcess(new NetplayProcess.Grant(1,id(1),false,false),()->new byte[0],c->{});
        process.inputRetroPad(65535);process.input(255);
        assertFalse(process.grant().host());assertFalse(process.grant().player());
        try{var input=NetplayProcess.class.getDeclaredField("input");input.setAccessible(true);assertEquals(0,input.getInt(process));}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
        process.close();
    }
    @Test void productionWiringKeepsReadOnlyGrantsAndSuppressesDuplicateMedia()throws Exception{
        var dir=Path.of("src/main/java/cn/piq/fcarcade");
        String service=Files.readString(dir.resolve("cabinet/WatchService.java"));String grants=Files.readString(dir.resolve("cabinet/WatchNetplay.java"));
        String transfer=Files.readString(dir.resolve("cabinet/CabinetSharedGameService.java"));String client=Files.readString(dir.resolve("client/watch/WatchClient.java"));
        assertTrue(grants.contains("grantObserver("));assertTrue(grants.contains("WatchService.authorized(e.player(),e.watch())"));
        assertTrue(grants.contains("revoke(e.connection(),e.ticket())"));
        assertTrue(service.contains("WatchNetplay.contains(server,lease.token()))continue"));
        assertTrue(service.contains("!WatchNetplay.contains(server,l.token())"));
        assertTrue(transfer.contains("WatchNetplay.gameTarget(p,lease,backend)"));assertTrue(transfer.contains("!CabinetRooms.canConfigureGame(p,c.lease())"));
        assertTrue(client.contains("grant.ticket(),false,false"));assertTrue(client.contains("NetplayNetwork.unbind(connection,netplay)"));
        assertFalse(client.contains("inputRetroPad("));assertFalse(client.contains("InputOwnership.acquire("));
    }
}
