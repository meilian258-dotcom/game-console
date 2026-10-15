// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Supplements disk/codec tests with cross-component routing and authority wiring checks. */
class SfcSaveLibraryWiringTest {
    private String source(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/server/"+file+".java"));}
    @Test void grantIsCopiedFromServerOwnedComputerAndCardNotTheIncomingPacket()throws Exception{
        String editor=source("SfcCartridgeEditorService");
        assertTrue(editor.contains("if(e==null||!e.token.equals(a.token()))return;if(!valid(p,e))"));
        assertTrue(editor.contains("Edit snapshot=new Edit(p,e.hand,e.stack,e.computer,st.tick)"));
        assertTrue(editor.contains("UUID card=SfcCartridgeData.id(e.stack);String rom=SfcCartridgeData.romSha(e.stack)"));
        assertTrue(editor.contains("STATES.get(server)==st"));
        assertTrue(editor.contains("p.containerMenu==p.inventoryMenu&&valid(p,snapshot)"));
        String service=source("SfcSaveLibraryService");
        assertTrue(service.contains("CartridgeSaveNetwork.registerServerHandlers(SYSTEM"));
        assertTrue(service.contains("if(lease==null||lease.player!=player)return"));
        assertTrue(service.contains("request.operation()!=CartridgeSaveNetwork.REFRESH"));
        assertTrue(service.contains("NetplaySaveServer.busy(server,row.slotKey()),false"));
        assertFalse(service.contains("Files.write"));assertFalse(service.contains("Files.delete"));
        assertFalse(service.contains("PREPARE_DELETE"));assertFalse(service.contains("CONFIRM_DELETE"));
    }
    @Test void asyncResultsAndRoutesAreBoundedAndRecheckedAfterClosingOriginalEditor()throws Exception{
        String service=source("SfcSaveLibraryService");
        for(String guard:new String[]{"state.leases.size()>=4","state.tick<lease.expires","expires=tick+1200","lease.loading||state.tick-lease.lastAction<20",
                "STATES.get(server)==state&&state.leases.get(lease.token)==lease","HomeLaunchIo.read(server,lease.catalog::inspect","CartridgeSaveNetwork.forgetRoute(lease.token)","state.leases.clear()"})assertTrue(service.contains(guard),guard);
        assertEquals(2,service.lines().filter(line->line.contains("if(!valid(server,state,lease)){if(state.leases.get(lease.token)==lease)")).count());
        assertTrue(source("SfcHomeServer").contains("SfcSaveLibraryService.register()"));
        String editor=source("SfcCartridgeEditorService");assertTrue(editor.contains("SfcSaveLibraryService.tick(server)"));assertTrue(editor.contains("SfcSaveLibraryService.close(s)"));
    }
}
