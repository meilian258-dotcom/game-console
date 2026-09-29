package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetControl766Test {
    @Test void hostCanSuspendInputWithoutRemovingRoom(){
        var ledger=new CabinetRoomLedger<String>();var player=UUID.randomUUID();var room=ledger.open(player,"cabinet","piq_native_arcade:mame",4,0);
        ledger.ready(player,room.id,room.host().id,0);room.coinRequired=true;
        var change=ledger.input(player,room.id,room.host().id,0,20,1);assertEquals(16,change.mask());
        room.host().controlling=false;assertEquals(0,ledger.input(player,room.id,room.host().id,1,20,2).mask());
        assertSame(room,ledger.get(room.id));assertTrue(room.ready);
        room.host().controlling=true;assertEquals(16,ledger.input(player,room.id,room.host().id,2,20,3).mask());
    }
    @Test void metadataRejectsControlsAndOversize(){
        CabinetGameInfo.check("contra.zip · 会话已开启");
        assertThrows(IllegalArgumentException.class,()->CabinetGameInfo.check("a\nb"));
        assertThrows(IllegalArgumentException.class,()->CabinetGameInfo.check("x".repeat(193)));
    }
}
