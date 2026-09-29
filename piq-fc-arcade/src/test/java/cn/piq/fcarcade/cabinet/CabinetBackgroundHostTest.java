package cn.piq.fcarcade.cabinet;

import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetBackgroundHostTest {
    @Test void releasedControlsCannotInjectInputsWhileHeartbeatRetainsRoom(){
        var ledger=new CabinetRoomLedger<String>();var player=UUID.randomUUID();var room=ledger.open(player,"cabinet","piq_native_arcade:mame",4,0);
        assertTrue(ledger.ready(player,room.id,room.id,1));var host=room.host();
        assertNotNull(ledger.input(player,room.id,host.id,0,16,2));host.controlling=false;
        assertEquals(0,ledger.reset(host).mask());assertNull(ledger.input(player,room.id,host.id,1,4095,3));assertEquals(0,host.mask);
        for(int time=10;time<1000;time+=10)assertTrue(ledger.heartbeat(player,host.id,time));
        assertSame(room,ledger.get(room.id));assertSame(host,ledger.valid(player,room.id,host.id,999));
        host.controlling=true;assertEquals(16,ledger.input(player,room.id,host.id,2,16,1000).mask());
    }
    @Test void distanceExceptionIsOnlyReadyNativeHostAndPhysicalActionsStillRequireRange()throws Exception{
        var r=Path.of("src/main/java/cn/piq/fcarcade");var rooms=Files.readString(r.resolve("cabinet/CabinetRooms.java"));var server=Files.readString(r.resolve("cabinet/ServerCabinets.java"));
        assertTrue(rooms.contains("room.ready&&member==room.host()&&CabinetCoinPolicy.supported(room.backend)"));
        assertTrue(server.contains("!computingHost&&!withinControlRange"));
        assertFalse(rooms.contains("own.controlling&&binding.cabinet().autoPowerOffOnExit()"));
        assertTrue(rooms.contains("copyServerRules(room,CabinetServerSettings.rules(server))"));
        assertTrue(rooms.contains("member.controlling=false;forward(server,state,state.ledger.reset(member))"));
    }
    @Test void switchRequestsHaveNoClientPositionAndRepeatOcclusionAfterPermission()throws Exception{
        var r=Path.of("src/main/java/cn/piq/fcarcade");var n=Files.readString(r.resolve("cabinet/CabinetNetwork.java"));
        var p=Files.readString(r.resolve("cabinet/CabinetPowerPicking.java"));var s=Files.readString(r.resolve("cabinet/ServerCabinets.java"));
        assertTrue(n.contains("record PowerPress()"));assertTrue(p.contains("Math.min(6,player.blockInteractionRange())"));
        assertTrue(p.contains("loaded.clip"));assertTrue(p.contains("CabinetBodyPicking.firstHit"));assertTrue(p.contains("Blocks.BARRIER.defaultBlockState()"));
        assertTrue(s.indexOf("if(!valid(player,binding,true))")<s.indexOf("var currentPowerHit=CabinetPowerPicking.pick(player)"));
    }
}
