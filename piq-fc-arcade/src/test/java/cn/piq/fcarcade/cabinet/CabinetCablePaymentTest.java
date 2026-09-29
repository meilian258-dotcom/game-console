package cn.piq.fcarcade.cabinet;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetCablePaymentTest {
    @Test void paidLinkRoundTripsAndCanOnlyRefundOnce(){
        var d=new CabinetLinks.Data();
        var a=new CabinetLinkLedger.End("minecraft:overworld",0,64,0,UUID.randomUUID(),false);
        var b=new CabinetLinkLedger.End("minecraft:overworld",1,64,0,UUID.randomUUID(),false);
        var pair=d.ledger.connect(a,b,true,true,2);assertNotNull(pair);assertTrue(d.paid.record(pair.id()));
        var raw=d.save(new CompoundTag(),null);var copy=CabinetLinks.Data.load(raw,null);
        assertEquals(pair,copy.ledger.find(a));assertNotNull(copy.ledger.disconnect(a,true,true));assertTrue(copy.paid.claim(pair.id()));
        assertNull(copy.ledger.disconnect(b,true,true));assertFalse(copy.paid.claim(pair.id()));
        raw.getList("Pairs",10).getCompound(0).remove("PaidCable");assertFalse(CabinetLinks.Data.load(raw,null).paid.claim(pair.id()));
    }
}
