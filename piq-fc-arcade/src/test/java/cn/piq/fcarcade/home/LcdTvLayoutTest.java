package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LcdTvLayoutTest {
    @Test void everyFacingStaysInsideOneBlockAndIncludesTheThinBodyAndStand() {
        for (int turn = -4; turn < 8; turn++) {
            var b = LcdTvLayout.bounds(turn);
            assertTrue(b.minX() >= 0 && b.minY() >= 0 && b.minZ() >= 0);
            assertTrue(b.maxX() <= 16 && b.maxY() <= 16 && b.maxZ() <= 16);
            assertEquals(13.5, b.maxY());
            assertEquals(16, Math.max(b.maxX()-b.minX(), b.maxZ()-b.minZ()), 1e-9);
            assertEquals(6.4, Math.min(b.maxX()-b.minX(), b.maxZ()-b.minZ()), 1e-9);
            assertEquals(LcdTvLayout.bounds(Math.floorMod(turn, 4)), b);
        }
    }

    @Test void quarterTurnRotatesTheReviewedPhysicalCornersAroundVanillaCentre() {
        for (int turns = 0; turns < 4; turns++) {
            var b = LcdTvLayout.bounds(turns);
            for (double x : new double[]{0, 16}) for (double z : new double[]{4.8, 11.2}) {
                double px = x, pz = z;
                for (int i = 0; i < turns; i++) { double nextX = 16-pz; pz=px; px=nextX; }
                assertTrue(px >= b.minX()-1e-9 && px <= b.maxX()+1e-9);
                assertTrue(pz >= b.minZ()-1e-9 && pz <= b.maxZ()+1e-9);
            }
        }
    }

    @Test void singleCellAdapterBypassesCrtAssemblyBeforeAnyLedgerMutation() throws Exception {
        String structure = source("home/HomeTvStructure");
        String complete = between(structure, "public static boolean complete(", "static boolean canPlace(");
        assertTrue(complete.indexOf("if (singleBlock(tv.getBlockState()))") < complete.indexOf("HomeTvAssemblyData.get(server)"));
        String removed = between(structure, "static void removed(Level level, BlockPos pos, BlockState previous)", "static void confirmPlacement(");
        assertTrue(removed.indexOf("if (singleBlock(previous))") < removed.indexOf("data.ledger.cancelPlacement("));
        assertTrue(removed.contains("if (!level.restoringBlockSnapshots) HomeHardware.removed(level, pos)"));
        assertTrue(structure.contains("tv && !singleBlock(tv.getBlockState())"));
        assertTrue(structure.contains("singleBlock(tv.getBlockState()) || !tv.structureInstalled()"));
        assertTrue(source("home/HomeTvBlockEntity").contains("HomeTvStructure.singleBlock(getBlockState()) || structureInstalled"));
    }

    @Test void lcdUsesSharedEndpointButVanillaSingleDropAndPreservesCrtDefault() throws Exception {
        String block = source("home/RetroTvBlock");
        assertTrue(block.contains("public boolean singleBlockTv() { return false; }"));
        assertTrue(block.contains("if (singleBlockTv()) return state"));
        assertTrue(block.contains("if (singleBlockTv()) return super.getDrops(state, context)"));
        assertTrue(block.contains("if (singleBlockTv()) return super.onDestroyedByPlayer("));
        assertTrue(block.contains("HomeTvStructure.removed(level, pos, state)"));
        assertTrue(block.contains("state.setValue(CENTERED, false)"));
        assertTrue(source("home/LcdTvBlock").contains("extends RetroTvBlock"));
        assertTrue(source("home/LcdTvBlock").contains("public boolean singleBlockTv() { return true; }"));
        String registrations=source("registry/ModBlockEntities");
        for (String id : java.util.List.of("RETRO_TV", "LCD_TV", "WIDE_LCD_TV", "LARGE_LCD_TV", "VINTAGE_TV"))
            assertTrue(registrations.contains("ModBlocks."+id+".get()"));
        assertTrue(source("home/LcdTvBlockItem").contains("DataComponents.BLOCK_ENTITY_DATA"));
        assertTrue(source("home/LcdTvBlockItem").contains("DataComponents.BLOCK_STATE"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade", name + ".java"));
    }
    private static String between(String text, String first, String last) {
        int start = text.indexOf(first), end = text.indexOf(last, start);
        assertTrue(start >= 0 && end > start); return text.substring(start, end);
    }
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (Object suite : java.util.List.of(new LcdTvLayoutTest(), new HomeHardwareLootResourceTest()))
            for (var method : suite.getClass().getDeclaredMethods())
                if (method.isAnnotationPresent(Test.class)) { method.invoke(suite); count++; }
        System.out.println("Passed " + count + " LCD/shared hardware checks.");
    }
}
