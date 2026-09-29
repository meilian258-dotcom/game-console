package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroBackendRegistry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Minecraft ResourceLocation contract; compiled only by the dedicated real-MC runner. */
class CabinetRetroRegistryTest {
    @Test void oldRegistrationApiUsesActualSharedTableAndPreservesStableIds()throws Exception{
        var backing=CabinetBackends.class.getDeclaredField("ENTRIES");backing.setAccessible(true);
        assertInstanceOf(RetroBackendRegistry.class,backing.get(null));
        assertEquals(16,CabinetBackends.MAX_BACKENDS);
        assertEquals("piq_fc_arcade:nes",CabinetBackends.NES.toString());
        assertFalse(CabinetBackends.find(CabinetBackends.NES).localOnly());
        assertNull(CabinetBackends.find(null));assertNull(CabinetBackends.find(ResourceLocation.parse("piq_qa:absent")));
        var id=ResourceLocation.parse("piq_qa:shared_registry");
        var before=CabinetBackends.entries();
        CabinetBackends.register(id,"Shared test provider",true);
        assertEquals(before.size()+1,CabinetBackends.entries().size());
        assertFalse(before.stream().anyMatch(entry->entry.id().equals(id)));
        assertEquals(new CabinetBackends.Entry(id,"Shared test provider",true),CabinetBackends.find(id));
        assertThrows(IllegalArgumentException.class,()->CabinetBackends.register(id,"Replacement",false));
        assertTrue(CabinetBackends.find(id).localOnly());
        assertThrows(UnsupportedOperationException.class,()->before.clear());
        assertNotNull(((RetroBackendRegistry)backing.get(null)).find(id.toString()));
    }
}
