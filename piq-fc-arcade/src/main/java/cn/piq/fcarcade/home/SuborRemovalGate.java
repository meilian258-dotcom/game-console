package cn.piq.fcarcade.home;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class SuborRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private SuborRemovalGate() {}

    static Result attempt(SuborFootprint.Facing facing, int clickedPart,
                          Predicate<SuborFootprint.Cell> loaded,
                          Predicate<SuborFootprint.Cell> permitted,
                          Predicate<SuborFootprint.Cell> owned,
                          Predicate<SuborFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        return attempt(facing, false, clickedPart, loaded, permitted, owned, breakEvent, originalUnchanged, commit);
    }

    static Result attempt(SuborFootprint.Facing facing, boolean compact, int clickedPart,
                          Predicate<SuborFootprint.Cell> loaded,
                          Predicate<SuborFootprint.Cell> permitted,
                          Predicate<SuborFootprint.Cell> owned,
                          Predicate<SuborFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        if (clickedPart < 0 || clickedPart >= SuborFootprint.partCount(compact)) return Result.REPLACED;
        var cells = SuborFootprint.cells(facing, compact);
        // Do not fire even an extra protection event until all occupied chunks and
        // basic permissions are available. Never use a predicate to load chunks.
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        for (var cell : cells) if (!permitted.test(cell)) return Result.DENIED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        for (var cell : cells) {
            if (cell.part() != clickedPart && owned.test(cell) && !breakEvent.test(cell)) return Result.DENIED;
        }
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        commit.run();
        return Result.REMOVED;
    }
}
