package cn.piq.fcarcade.server;

import cn.piq.fcarcade.core.libretro.GenericLibretroNesCore;
import cn.piq.fcarcade.fixtures.NativeSaveTestRom;
import cn.piq.fcarcade.home.CartridgeSaveIdentity;
import cn.piq.fcarcade.session.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveOwnership65Test {
    @TempDir Path root;
    @Test void bundleUsesExistingPersonalAndCartridgeOwnership() {
        byte[] saved;
        try(var core=new GenericLibretroNesCore(false)){
            core.loadRom(NativeSaveTestRom.bytes());core.runFrame();saved=core.savePersistentState();
        }
        var store=new ArcadeSaveStore(root);var variant=NesCoreVariant.LIBRETRO_V1;
        UUID player=UUID.randomUUID(),other=UUID.randomUUID(),card=UUID.randomUUID();
        String personal=variant.saveKey(PlayerSaveSlots.key(player,1));
        String otherSlot=variant.saveKey(PlayerSaveSlots.key(other,1));
        String cartridge=variant.saveKey(CartridgeSaveIdentity.key(card));
        store.save(personal,NativeSaveTestRom.sha(),saved,"我的进度",1);
        store.save(cartridge,NativeSaveTestRom.sha(),saved,"卡带进度",2);
        assertNull(store.loadReadOnly(otherSlot,NativeSaveTestRom.sha()));
        assertTrue(CartridgeSaveIdentity.owns(cartridge,card));
        assertFalse(CartridgeSaveIdentity.owns(cartridge,UUID.randomUUID()));
        for(String key:new String[]{personal,cartridge})try(var core=new GenericLibretroNesCore(false)){
            core.loadRom(NativeSaveTestRom.bytes());core.loadPersistentState(store.loadReadOnly(key,NativeSaveTestRom.sha()));
            assertEquals(0x5a,NesPersistentState.decode(core.savePersistentState()).memory().ram()[0]&255);
        }
        assertEquals(2,store.list().size());
    }
}
