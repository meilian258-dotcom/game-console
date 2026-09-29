package cn.piq.retro.input;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputOwnershipWatchTest {
    @Test void occupancyCheckCannotAcquireReleaseOrReplacePlayer() {
        Object player = new Object(), other = new Object();
        assertFalse(InputOwnership.occupied());
        try {
            assertTrue(InputOwnership.acquire(player));
            for (int i = 0; i < 1000; i++) assertTrue(InputOwnership.occupied());
            assertTrue(InputOwnership.owns(player));
            assertFalse(InputOwnership.acquire(other));
            InputOwnership.release(other);
            assertTrue(InputOwnership.occupied());
        } finally { InputOwnership.release(player); }
        assertFalse(InputOwnership.occupied());
    }
}
