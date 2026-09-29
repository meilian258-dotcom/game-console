package cn.piq.fcarcade.world;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class DualCabinetRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private DualCabinetRemovalGate() {}

    static Result attempt(DualCabinetFootprint.Facing facing, int clickedPart,
                          Predicate<DualCabinetFootprint.Cell> loaded,
                          Predicate<DualCabinetFootprint.Cell> permitted,
                          Predicate<DualCabinetFootprint.Cell> owned,
                          Predicate<DualCabinetFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        return attempt(facing, false, clickedPart, loaded, permitted, owned, breakEvent, originalUnchanged, commit);
    }

    static Result attempt(DualCabinetFootprint.Facing facing, boolean compact, int clickedPart,
                          Predicate<DualCabinetFootprint.Cell> loaded,
                          Predicate<DualCabinetFootprint.Cell> permitted,
                          Predicate<DualCabinetFootprint.Cell> owned,
                          Predicate<DualCabinetFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        if (clickedPart < 0 || clickedPart >= DualCabinetFootprint.count(compact)) return Result.REPLACED;
        var cells = DualCabinetFootprint.cells(facing, compact);
        // Do not fire even an extra protection event until all occupied chunks and
        // basic permissions are available. Never use a predicate to load chunks.
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        for (var cell : cells) if (!permitted.test(cell)) return Result.DENIED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        for (var cell : cells) {
            if (cell.part() != clickedPart && owned.test(cell) && !breakEvent.test(cell)) return Result.DENIED;
        }
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        for (var cell : cells) if (!permitted.test(cell)) return Result.DENIED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        commit.run();
        return Result.REMOVED;
    }
}
