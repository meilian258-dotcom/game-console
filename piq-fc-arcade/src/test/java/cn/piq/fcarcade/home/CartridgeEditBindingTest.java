package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeEditBindingTest {
    @Test void mainAndOffhandBindingsHaveDistinctCorrectSlotRules() {
        UUID token = UUID.randomUUID(), id = UUID.randomUUID();
        CartridgeEditBinding main = new CartridgeEditBinding(token, id, 0, 2), off = new CartridgeEditBinding(token, id, 1, 40);
        assertTrue(main.permits(main, id, 2, true, true, true)); assertTrue(off.permits(off, id, 40, true, true, true));
        assertFalse(main.permits(off, id, 40, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new CartridgeEditBinding(token, id, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new CartridgeEditBinding(token, id, 0, 40));
    }
    @Test void copiedNbtIdentityCannotSubstituteAnotherPhysicalStack() {
        UUID id = UUID.randomUUID(); CartridgeEditBinding binding = new CartridgeEditBinding(UUID.randomUUID(), id, 0, 0);
        assertFalse(binding.permits(binding, id, 0, false, true, true));
        assertFalse(binding.permits(binding, UUID.randomUUID(), 0, true, true, true));
    }
    @Test void staleTokenSwapSlotDeathAndPermissionRevocationAllReject() {
        UUID id = UUID.randomUUID(); CartridgeEditBinding binding = new CartridgeEditBinding(UUID.randomUUID(), id, 0, 1);
        assertFalse(binding.permits(new CartridgeEditBinding(UUID.randomUUID(), id, 0, 1), id, 1, true, true, true));
        assertFalse(binding.permits(binding, id, 2, true, true, true));
        assertFalse(binding.permits(binding, id, 1, true, false, true));
        assertFalse(binding.permits(binding, id, 1, true, true, false));
    }
    @Test void metadataRejectsArbitraryPathsUppercaseHashesAndOversizedTitles() {
        assertEquals("", CartridgeLimits.hashOrEmpty("")); assertTrue(CartridgeLimits.validHash("a".repeat(64)));
        assertFalse(CartridgeLimits.validHash("../secret.png")); assertFalse(CartridgeLimits.validHash("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> CartridgeLimits.cleanTitle("a".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> CartridgeLimits.cleanTitle("game\nname"));
        assertEquals("game", CartridgeLimits.cleanTitle(" game "));
    }
}
