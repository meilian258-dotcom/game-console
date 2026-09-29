package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeComputerBindingTest {
    private static final UUID ID = UUID.randomUUID();
    private static final CartridgeComputerBinding BINDING = new CartridgeComputerBinding(ID, "minecraft:overworld", 10, 64, 20);
    private static boolean permitted(UUID id, String dimension, boolean same, boolean loaded, boolean alive, boolean op, boolean allowed, double distance) {
        return BINDING.permits(id, dimension, same, loaded, alive, op, allowed, distance);
    }
    @Test void exactFiveBlockBoundaryIsAllowedButAnythingBeyondIsDenied() {
        assertTrue(permitted(ID, BINDING.dimension(), true, true, true, true, true, 0));
        assertTrue(permitted(ID, BINDING.dimension(), true, true, true, true, true, 25));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, true, true, Math.nextUp(25.0)));
    }
    @Test void nonFiniteAndNegativeDistanceCannotBypassTheRangeGate() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1})
            assertFalse(permitted(ID, BINDING.dimension(), true, true, true, true, true, value));
    }
    @Test void samePositionReplacementAndClonedUuidStillRequireOriginalLiveInstance() {
        assertFalse(permitted(ID, BINDING.dimension(), false, true, true, true, true, 1));
        assertFalse(permitted(UUID.randomUUID(), BINDING.dimension(), true, true, true, true, true, 1));
        assertFalse(permitted(null, BINDING.dimension(), false, false, true, true, true, 1));
    }
    @Test void DimensionAndUnloadChangesInvalidateInsteadOfLoadingTheComputer() {
        assertFalse(permitted(ID, "minecraft:the_nether", true, true, true, true, true, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, false, true, true, true, 1));
    }
    @Test void DeathSpectatorOperatorLossAndProtectionDenialAreIndependentVetoes() {
        assertFalse(permitted(ID, BINDING.dimension(), true, true, false, true, true, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, false, true, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, true, false, 1));
    }
    @Test void BoundariesAreRecheckedForEveryOperationNotJustTheOpening() {
        assertTrue(permitted(ID, BINDING.dimension(), true, true, true, true, true, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, false, true, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, true, false, 1));
        assertFalse(permitted(ID, BINDING.dimension(), true, true, true, true, true, 26));
    }
    @Test void MalformedStationIdentityIsNotAValidLease() {
        assertThrows(IllegalArgumentException.class, () -> new CartridgeComputerBinding(null, "world", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CartridgeComputerBinding(new UUID(0, 0), "world", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CartridgeComputerBinding(ID, "", 0, 0, 0));
    }
    @Test void OnlyOneOrTwoPlayersForAnExistingIdleRomCanBeChanged() {
        assertTrue(CartridgeComputerBinding.permitsPlayersSetting(1, true, false));
        assertTrue(CartridgeComputerBinding.permitsPlayersSetting(2, true, false));
        for (int players : new int[]{Integer.MIN_VALUE, -1, 0, 3, Integer.MAX_VALUE})
            assertFalse(CartridgeComputerBinding.permitsPlayersSetting(players, true, false));
        assertFalse(CartridgeComputerBinding.permitsPlayersSetting(2, false, false));
        assertFalse(CartridgeComputerBinding.permitsPlayersSetting(2, true, true));
    }
}
