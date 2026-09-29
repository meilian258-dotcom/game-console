package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeCartridgeSlotTest {
    @Test
    void insertionCannotOverwriteAnOccupiedPhysicalSlot() {
        var slot = new HomeCartridgeSlot<String>();
        assertTrue(slot.insert("card-one"));
        assertFalse(slot.insert("card-two"));
        assertEquals("card-one", slot.value());
        assertEquals("card-one", slot.take());
        assertTrue(slot.insert("card-two"));
        assertEquals("card-two", slot.take());
    }

    @Test
    void ejectionAndRepeatedRemovalCallbacksDeliverOneCartridge() {
        var slot = new HomeCartridgeSlot<String>();
        slot.restore("persisted-card");
        assertEquals("persisted-card", slot.take());
        assertFalse(slot.occupied());
        assertNull(slot.take());
        assertNull(slot.take());
    }

    @Test
    void unloadedSnapshotCanBeRestoredWithoutEjectingOrDuplicating() {
        var before = new HomeCartridgeSlot<String>();
        assertFalse(before.insert(null));
        before.insert("card");
        String persisted = before.value();
        assertTrue(before.occupied());
        var afterLoad = new HomeCartridgeSlot<String>();
        afterLoad.restore(persisted);
        assertEquals("card", afterLoad.take());
        assertNull(afterLoad.take());
    }
}
