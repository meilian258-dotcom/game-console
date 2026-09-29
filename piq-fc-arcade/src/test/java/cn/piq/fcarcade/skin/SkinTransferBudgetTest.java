package cn.piq.fcarcade.skin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkinTransferBudgetTest {
    @Test
    void supportsNewTemplateButCapsParallelRetainedBuffers() {
        assertTrue(SkinTransferBudget.canStart(0, 0, 1_053_354));
        assertTrue(SkinTransferBudget.canStart(1, 16L * 1024 * 1024, 16 * 1024 * 1024));
        assertFalse(SkinTransferBudget.canStart(2, 32L * 1024 * 1024, 1));
        assertFalse(SkinTransferBudget.canStart(4, 4, 1));
        assertFalse(SkinTransferBudget.canStart(0, 0, SkinTransferLimits.MAX_PNG_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> new SkinTransferBuffer(
                "oversized", "a".repeat(64), SkinTransferLimits.MAX_PNG_BYTES + 1));
    }

    @Test
    void layoutIdRoundTripKeepsLegacyAndCurrentDistinct() {
        for (SkinLayout layout : SkinLayout.values()) assertEquals(layout, SkinLayout.fromId(layout.id()));
        assertThrows(IllegalArgumentException.class, () -> SkinLayout.fromId(999));
        assertFalse(new SkinDescriptor("old", "a".repeat(64), 100, SkinLayout.LEGACY_512).compatible());
        assertTrue(new SkinDescriptor("new", "a".repeat(64), 1_053_354, SkinLayout.ROCKET_V1_2048).compatible());
    }
}
