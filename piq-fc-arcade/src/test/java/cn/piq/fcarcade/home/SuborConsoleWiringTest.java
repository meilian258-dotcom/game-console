package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** The unit VM deliberately has no Minecraft registry: guard the shared production wiring without bootstrapping it. */
class SuborConsoleWiringTest {
    @Test void newShellInheritsAllOriginalHardwareTransactionsAndOnlyChangesRenderingAndShape() throws Exception {
        String subor = source("home/SuborConsoleBlock");
        String original = source("world/FamicomConsoleBlock");
        assertTrue(subor.contains("extends FamicomConsoleBlock"));
        assertTrue(original.contains("public class FamicomConsoleBlock"));
        for (String inherited : new String[]{"newBlockEntity(", "getTicker(", "useWithoutItem(", "onRemove(",
                "rotate(", "mirror("}) {
            assertTrue(original.contains(inherited), inherited);
            assertFalse(subor.contains(inherited), "Do not fork shared hardware behavior: " + inherited);
        }
        assertTrue(subor.contains("HomeConsoleLayout.suborBounds(turns)"));
        assertTrue(subor.contains("setValue(WIDE, false)"));
        assertTrue(subor.contains("setValue(WIDE, true)"));
        assertTrue(subor.contains("else super.removeHardware(state, level, pos)"));
        assertTrue(subor.contains("if (wide(state)) SuborStructure.removed(level, pos)"));
        assertTrue(subor.contains("RenderShape.ENTITYBLOCK_ANIMATED"));
        assertFalse(original.contains("RenderShape.ENTITYBLOCK_ANIMATED"));
        assertTrue(source("home/AvCableItem").contains("block instanceof FamicomConsoleBlock"));
        assertTrue(source("home/FcControllerItem").contains("block instanceof cn.piq.fcarcade.world.FamicomConsoleBlock"));
        assertTrue(source("home/FcCartridgeItem").contains("instanceof HomeConsoleBlockEntity"));
    }

    @Test void newBlockItemAndExistingBlockEntityTypeAreRegisteredWithoutReplacingTheOriginal() throws Exception {
        String blocks = source("registry/ModBlocks");
        String items = source("registry/ModItems");
        String entities = source("registry/ModBlockEntities");
        for (String id : new String[]{"famicom_console", "subor_console"}) {
            assertTrue(blocks.contains("BLOCKS.register(\"" + id + "\""));
            assertTrue(items.contains("ITEMS.register(\"" + id + "\""));
        }
        assertTrue(entities.contains("Set.of(ModBlocks.FAMICOM_CONSOLE.get(), ModBlocks.SUBOR_CONSOLE.get())"));
        assertTrue(entities.contains("BLOCK_ENTITIES.register(\"subor_part\""));
        assertFalse(entities.contains("BLOCK_ENTITIES.register(\"subor_console\""));
        assertFalse(items.contains("ITEMS.register(\"subor_part\""));
        assertTrue(source("FcArcadeMod").contains("event.accept(ModItems.SUBOR_CONSOLE.get())"));
        assertTrue(source("registry/ModCreativeTabs").contains("CreativeTabCatalog.itemPaths("));
        assertTrue(source("registry/CreativeTabCatalog").contains("\"subor_console\""));
    }

    @Test void compactPlacementIsExplicitWhileMissingBlockAndLedgerFieldsStayLegacy() throws Exception {
        String block = source("home/SuborConsoleBlock");
        assertTrue(block.contains("BooleanProperty.create(\"compact\")"));
        assertTrue(block.contains("setValue(WIDE, false).setValue(COMPACT, false)"));
        assertTrue(block.contains("setValue(WIDE, true).setValue(COMPACT, true)"));
        assertTrue(source("home/SuborPartBlock").contains("setValue(SuborConsoleBlock.COMPACT, false)"));
        String persistence = source("home/SuborAssemblyData");
        assertTrue(persistence.contains("entry.getBoolean(\"Compact\")"));
        assertTrue(persistence.contains("entry.putBoolean(\"Compact\", assembly.compact())"));
        String structure = source("home/SuborStructure");
        assertTrue(structure.contains(".setValue(SuborConsoleBlock.COMPACT, SuborConsoleBlock.compact(anchor))"));
        assertTrue(structure.contains("entry.compact() != compact"));
        assertTrue(structure.contains("SuborFootprint.cells(entry.facing(), entry.compact())"));
        assertTrue(structure.contains("SuborRemovalGate.attempt(assembly.facing(), assembly.compact(), actual.part()"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade", name + ".java"));
    }
}
