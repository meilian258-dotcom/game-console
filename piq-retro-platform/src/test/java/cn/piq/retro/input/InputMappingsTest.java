package cn.piq.retro.input;

import org.junit.jupiter.api.Test;

import static cn.piq.retro.input.RetroButtons.*;
import static org.junit.jupiter.api.Assertions.*;

class InputMappingsTest {
    @Test void allCanonicalMasksKeepTheExistingSfcAndArcadeProtocol() {
        for (int mask = 0; mask <= MASK; mask++) {
            assertEquals(mask, InputMappings.sfc12(mask));
            assertEquals(mask, InputMappings.arcade12(mask));
            int nes = 0;
            if ((mask & A) != 0) nes |= 1;
            if ((mask & B) != 0) nes |= 2;
            for (int bit = 2; bit < 8; bit++) if ((mask & (1 << bit)) != 0) nes |= 1 << bit;
            assertEquals(nes, InputMappings.nes8(mask));
        }
    }

    @Test void explicitSixActionRepresentationIsNeverImplicitlyUsedForExistingArcade() {
        int[] canonical = {UP, DOWN, LEFT, RIGHT, Y, B, A, X, L, R, START, SELECT};
        for (int i = 0; i < canonical.length; i++) {
            assertEquals(1 << i, InputMappings.arcade6Explicit(canonical[i]));
            assertEquals(canonical[i], InputMappings.arcade12(canonical[i]));
        }
        assertEquals(MASK, InputMappings.arcade6Explicit(MASK));
        assertNotEquals(InputMappings.arcade12(B), InputMappings.arcade6Explicit(B));
    }

    @Test void allMasksNeutralizeOnlyOppositeDirections() {
        for (int mask = 0; mask <= MASK; mask++) {
            int normalized = neutralizeOpposites(mask);
            int expected = mask;
            if ((mask & (UP | DOWN)) == (UP | DOWN)) expected &= ~(UP | DOWN);
            if ((mask & (LEFT | RIGHT)) == (LEFT | RIGHT)) expected &= ~(LEFT | RIGHT);
            assertEquals(expected, normalized);
            assertEquals(normalized, neutralizeOpposites(normalized));
        }
    }

    @Test void rejectBitsOutsideTheProtocol() {
        for (int invalid : new int[]{4096, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> InputMappings.nes8(invalid));
            assertThrows(IllegalArgumentException.class, () -> InputMappings.sfc12(invalid));
            assertThrows(IllegalArgumentException.class, () -> InputMappings.arcade12(invalid));
            assertThrows(IllegalArgumentException.class, () -> InputMappings.arcade6Explicit(invalid));
            assertThrows(IllegalArgumentException.class, () -> neutralizeOpposites(invalid));
        }
    }
}
