package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Mapper19CompatibilityTest {
    private static INesHeader board(int mapper, int prg, int chr) {
        return new INesHeader(INesHeader.Format.INES, mapper, 0, prg, chr, false, false);
    }
    @Test void acceptsTargetLayoutOnlyInDedicatedCore() {
        var header=board(19,128*1024,128*1024);
        assertTrue(NesCompatibility.isSupported(header));
        assertDoesNotThrow(()->NesCompatibility.requireMapper19Supported(header));
        assertThrows(IllegalArgumentException.class,()->NesCompatibility.requireLegacySupported(header));
    }
    @Test void preservesLegacyMapperSelection() {
        for(int mapper:new int[]{0,1,2,3,4,140}) {
            var header=board(mapper,32*1024,8*1024);
            assertTrue(NesCompatibility.isSupported(header));
            assertDoesNotThrow(()->NesCompatibility.requireLegacySupported(header));
            assertThrows(IllegalArgumentException.class,()->NesCompatibility.requireMapper19Supported(header));
        }
    }
    @Test void legacyNamcoStillRejectsUnimplementedVariantsAndOversizedOrChrRamBoards() {
        for(var header:new INesHeader[]{null,board(19,16*1024,8*1024),board(19,1024*1024,8*1024),
                board(19,128*1024,0),board(19,128*1024,512*1024),board(23,128*1024,128*1024),
                new INesHeader(INesHeader.Format.NES_2_0,19,0,128*1024,128*1024,false,false)}) {
            assertEquals(header != null, NesCompatibility.isSupported(header));
            assertThrows(IllegalArgumentException.class,()->NesCompatibility.requireMapper19Supported(header));
        }
    }
}
