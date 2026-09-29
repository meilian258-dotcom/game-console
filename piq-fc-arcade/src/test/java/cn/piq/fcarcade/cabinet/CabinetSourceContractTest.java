package cn.piq.fcarcade.cabinet;

import cn.piq.retro.api.RetroBackendRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Static wiring checks supplement the executable ownership ledger tests; not a Minecraft integration test. */
class CabinetSourceContractTest {
    private static String source(String relative)throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade",relative+".java"));
    }
    @Test void declarationsAndNetworkRemainCommonSide()throws Exception {
        for(String file:new String[]{"cabinet/CabinetBackends","cabinet/CabinetTarget","cabinet/CabinetNetwork","cabinet/ServerCabinets"}){
            String s=source(file);assertFalse(s.contains("net.minecraft.client"),file);assertFalse(s.contains("cn.piq.fcarcade.client"),file);
            assertFalse(s.contains("WasmNesCore"),file);assertFalse(s.contains("WasmSfcCore"),file);
        }
    }
    @Test void backendRegistryRejectsDuplicateAndUnboundedDeclarations()throws Exception {
        String s=source("cabinet/CabinetBackends");
        assertTrue(s.contains("MAX_BACKENDS = RetroBackendRegistry.MAX_BACKENDS"));
        assertTrue(s.contains("ENTRIES = new RetroBackendRegistry()"));
        assertTrue(s.contains("ENTRIES.register(entry.id().toString(), entry.displayName(), entry.localOnly())"));
        assertTrue(s.contains("displayName.length() > 64"));
        String shared=Files.readString(Path.of("../piq-retro-platform/src/main/java/cn/piq/retro/api/RetroBackendRegistry.java"));
        for(String expected:new String[]{"MAX_BACKENDS = 16","entries.containsKey(id)","entries.size() >= MAX_BACKENDS",
                "displayName.length() > 64","id.length() > 128","List.copyOf(entries.values())"})assertTrue(shared.contains(expected),expected);
        assertFalse(shared.contains("net.minecraft"));assertFalse(shared.contains("cn.piq.fcarcade"));
        // Exercise the real pure-Java registry too: delegation must not weaken its bounds.
        var registry=new RetroBackendRegistry();
        registry.register("piq_qa:first","First",true);
        var original=registry.find("piq_qa:first");
        assertThrows(IllegalArgumentException.class,()->registry.register("piq_qa:first","Replacement",false));
        assertSame(original,registry.find("piq_qa:first"));
        assertThrows(IllegalArgumentException.class,()->registry.register("piq_qa:label","x".repeat(65),true));
        assertThrows(IllegalArgumentException.class,()->registry.register("piq_qa:"+"x".repeat(129),"Long ID",true));
        for(int i=1;i<RetroBackendRegistry.MAX_BACKENDS;i++)registry.register("piq_qa:entry_"+i,"Entry "+i,false);
        assertEquals(16,registry.entries().size());
        assertThrows(IllegalStateException.class,()->registry.register("piq_qa:overflow","Overflow",false));
        assertEquals(16,registry.entries().size());assertNull(registry.find("piq_qa:overflow"));
    }
    @Test void menuAuthorityComesFromAnActualBoundClickNotPacketCoordinates()throws Exception {
        String s=source("cabinet/ServerCabinets");
        assertTrue(s.contains("MENU_TICKS=600"));
        for(String expected:new String[]{"!menu.token().equals(request.token())","state.menus.remove(player.getUUID())",
                "now(server)>=menu.expires()","level.getBlockEntity(target.anchor())!=binding.cabinet()",
                "level.getBlockEntity(binding.clicked())!=binding.clickedEntity()","valid(player,menu.binding(),true)"})assertTrue(s.contains(expected),expected);
        String network=source("cabinet/CabinetNetwork");
        assertTrue(network.contains("record Choose(UUID token,ResourceLocation backend)"));
        assertTrue(network.contains("size>CabinetBackends.MAX_BACKENDS"));
        assertTrue(network.contains("c.enqueueWork"));assertTrue(network.contains("readUtf(128)"));
    }
    @Test void targetExcludesTvsAndChecksDualOwnershipWithoutChunkLoading()throws Exception {
        String s=source("cabinet/CabinetTarget");
        assertTrue(s.contains("!(block instanceof LegacyFcArcadeBlock)"));
        assertTrue(s.contains("DualCabinetStructure.complete(level, anchor)"));
        assertTrue(s.contains("!level.hasChunkAt(clicked)"));assertTrue(s.contains("entity.cabinetId()"));
    }
    @Test void runtimeRevalidatesPermissionAndLocalModeAndStopsExactLease()throws Exception {
        String s=source("cabinet/ServerCabinets");
        for(String expected:new String[]{"!server.isDedicatedServer()&&!server.isPublished()","event.isCanceled()",
                "event.getUseBlock()!=TriState.FALSE","event.getUseItem()!=TriState.FALSE",
                "!level.mayInteract(player,binding.clicked())","!player.mayUseItemAt(target.anchor()",
                "now>=lease.expires()","lease.target().identity().equals(identity)","state.nextHeartbeat.put(id,now+5)"})assertTrue(s.contains(expected),expected);
    }
    @Test void nesRemainsDefaultAndItsOldRomMetadataIsNotRewritten()throws Exception {
        String s=source("world/LegacyFcArcadeBlockEntity");
        assertTrue(s.contains("private ResourceLocation cabinetBackend = CabinetBackends.NES"));
        assertTrue(s.contains("tag.putUUID(\"CabinetId\", cabinetId)"));
        assertTrue(s.contains("cabinetBackend = parsed == null ? CabinetBackends.NES : parsed"));
        assertFalse(s.contains("RomRepository"));assertFalse(s.contains("selectedRom"));
        String sessions=source("server/ServerArcadeSessions");
        assertTrue(sessions.contains("ServerCabinets.blocksNes(level, clickedPos)"));
        assertTrue(sessions.contains("ServerCabinets.hasExternalLease(player)"));
    }
    @Test void bothCabinetsAndLifecycleHooksActuallyCallTheService()throws Exception {
        assertTrue(source("world/FcArcadeBlock").contains("ServerCabinets.interact(serverPlayer, pos, hitResult)"));
        assertTrue(source("world/DualCabinetStructure").contains("ServerCabinets.interact(player, clicked, hit)"));
        assertTrue(source("world/LegacyFcArcadeBlock").contains("ServerCabinets.removed(serverLevel, pos, cabinet.cabinetId())"));
        assertTrue(source("world/DualCabinetBlock").contains("ServerCabinets.removed(server, pos, cabinet.cabinetId())"));
        assertTrue(source("world/DualCabinetBlockEntity").contains("super.onChunkUnloaded()"));
        assertTrue(source("FcArcadeMod").contains("CabinetNetwork::register"));
        assertTrue(source("FcArcadeMod").contains("ServerCabinets.register()"));
    }
}
