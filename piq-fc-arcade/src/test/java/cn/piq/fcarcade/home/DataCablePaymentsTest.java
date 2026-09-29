package cn.piq.fcarcade.home;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataCablePaymentsTest {
    @Test void oldFreeLinksAndRepeatedUnlinkNeverRefund(){var p=new DataCablePayments();var id=UUID.randomUUID();assertFalse(p.claim(id));assertTrue(p.record(id));assertFalse(p.record(id));assertTrue(p.claim(id));assertFalse(p.claim(id));}
    @Test void capacityIsBoundedAndClaimsReleaseSpace(){var p=new DataCablePayments();UUID first=null;for(int i=0;i<128;i++){var id=UUID.randomUUID();if(first==null)first=id;assertTrue(p.record(id));}assertFalse(p.record(UUID.randomUUID()));assertTrue(p.claim(first));assertTrue(p.record(UUID.randomUUID()));assertFalse(p.record(null));}
    @Test void zapperPaidReceiptPersistsButLegacyMissingFlagDoesNotBecomePaid(){
        var data=new ZapperStandService.Data();
        var a=new ZapperStandLinks.End("minecraft:overworld",0,64,0,UUID.randomUUID());
        var b=new ZapperStandLinks.End("minecraft:overworld",1,64,0,UUID.randomUUID());
        var link=data.links.connect(a,b);assertNotNull(link);assertTrue(data.paid.record(link.id()));
        var raw=data.save(new CompoundTag(),null);var loaded=ZapperStandService.Data.load(raw,null);
        assertEquals(link,loaded.links.at(a));assertTrue(loaded.paid.claim(link.id()));assertFalse(loaded.paid.claim(link.id()));
        raw.getList("Links",10).getCompound(0).remove("PaidCable");
        assertFalse(ZapperStandService.Data.load(raw,null).paid.claim(link.id()));
    }
    @Test void duplicateInvalidRowCannotGrantRefundToExistingFreeLink(){
        var a=new ZapperStandLinks.End("minecraft:overworld",0,64,0,UUID.randomUUID());var b=new ZapperStandLinks.End("minecraft:overworld",1,64,0,UUID.randomUUID());
        var link=new ZapperStandLinks.Link(UUID.randomUUID(),a,b);var free=ZapperStandService.Data.writeLink(link);var forged=free.copy();forged.putBoolean("PaidCable",true);
        var rows=new ListTag();rows.add(free);rows.add(forged);var raw=new CompoundTag();raw.put("Links",rows);
        assertFalse(ZapperStandService.Data.load(raw,null).paid.claim(link.id()));
    }
}
