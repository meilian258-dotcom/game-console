package cn.piq.fcarcade.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetWiringTest {
    private static String source(String name) throws Exception {return Files.readString(Path.of("src/main/java/cn/piq/fcarcade",name+".java"));}
    @Test void ordinaryFcSessionsRemainSharedAndOnlyExactAnchorIsClosed() throws Exception {
        var block=source("world/DualCabinetBlock");var structure=source("world/DualCabinetStructure");
        assertTrue(block.contains("extends FcArcadeBlock"));assertTrue(block.contains("ArcadeDisplayStyle.DUAL_CABINET"));
        assertTrue(block.contains("supportsFormation() { return false; }"));
        assertFalse(structure.contains("HomeHardware"));
        for(String api:new String[]{"ServerArcadeSessions.interact(player, anchor)","ServerArcadeSessions.openLibrary(player, anchor)",
                "ServerArcadeSessions.stopHomeConsole","ServerArcadeSessions.removeMachineDisplays","expected.equals(cabinet.assemblyId())"})
            assertTrue(structure.contains(api),api);
    }
    @Test void placementRepeatsPermissionCollisionAndExactRollbackGuards() throws Exception {
        var source=source("world/DualCabinetStructure");
        for(String guard:new String[]{"hasChunkAt","isOutsideBuildHeight","getWorldBorder().isWithinBounds","mayUseItemAt",
                "canBeReplaced","isUnobstructed","level.getBlockEntity(pos) != null",
                "level.getBlockState(cell.pos()) == cell.installed()","level.getBlockEntity(cell.pos()) == cell.entity()",
                "for (int i = placed.size() - 1; i >= 0; i--)","captureBlockSnapshots","restoringBlockSnapshots",
                "data.ledger.cancelPlacement","if (level.hasChunkAt(pos)) { data.ledger.acknowledge"})
            assertTrue(source.contains(guard),guard);
        assertFalse(source.contains("getChunk("));assertFalse(source.contains("HomeTvAssemblyData"));
    }
    @Test void proxyInteractionConsultsAnchorProtectionAndRechecksEntitiesAfterEvent() throws Exception {
        var source=source("world/DualCabinetStructure");
        for(String guard:new String[]{"PlayerInteractEvent.RightClickBlock","event.isCanceled()","TriState.FALSE",
                "CHECKING_ANCHOR.remove()","level.getBlockEntity(anchor) != cabinet","level.getBlockEntity(clicked) != clickedEntity",
                "DualCabinetFootprint.cell(facing(state), state.getValue(DualCabinetPartBlock.PART), compact(state))"})
            assertTrue(source.contains(guard),guard);
    }
    @Test void blockEntitiesHaveServerMaintenanceAndProtectedPersistentIdentity() throws Exception {
        for(String name:new String[]{"DualCabinetBlockEntity","DualCabinetPartBlockEntity"}) {
            var source=source("world/"+name);
            assertTrue(source.contains("onlyOpCanSetNbt() { return true; }"));
            assertTrue(source.contains("maintenanceTicks++ % 20 == 0"));
            if(name.equals("DualCabinetBlockEntity"))
                assertSame(LegacyFcArcadeBlockEntity.class,DualCabinetBlockEntity.class.getMethod("getUpdatePacket").getDeclaringClass());
            else assertTrue(source.contains("getUpdatePacket()"));
            assertTrue(source.contains("loadAdditional"));
        }
        assertTrue(source("world/DualCabinetAssemblyData").contains("piq_dual_cabinet_assemblies"));
        assertTrue(source("world/DualCabinetBlockItem").contains("DataComponents.BLOCK_ENTITY_DATA"));
        assertTrue(source("world/DualCabinetBlockItem").contains("DataComponents.BLOCK_STATE"));
    }
    @Test void registeredOnceWithNoProxyItemOrAutomaticAnchorLoot() throws Exception {
        assertTrue(source("registry/ModBlocks").contains("BLOCKS.register(\"dual_cabinet\""));
        assertTrue(source("registry/ModBlocks").contains("BLOCKS.register(\"dual_cabinet_part\""));
        assertTrue(source("registry/ModBlockEntities").contains("DualCabinetBlockEntity::new"));
        assertTrue(source("registry/ModBlockEntities").contains("DualCabinetPartBlockEntity::new"));
        assertTrue(source("registry/ModItems").contains("ITEMS.register(\"dual_cabinet\""));
        assertFalse(source("registry/ModItems").contains("ITEMS.register(\"dual_cabinet_part\""));
        assertTrue(source("registry/CreativeTabCatalog").contains("\"dual_cabinet\""));
        assertFalse(source("registry/CreativeTabCatalog").contains("\"dual_cabinet_part\""));
        assertTrue(source("world/DualCabinetBlock").contains("getDrops(BlockState state, LootParams.Builder context) { return List.of(); }"));
        assertTrue(Files.readString(Path.of("src/main/resources/data/piq_fc_arcade/loot_table/blocks/dual_cabinet.json")).contains("piq_fc_arcade:dual_cabinet"));
    }
}
