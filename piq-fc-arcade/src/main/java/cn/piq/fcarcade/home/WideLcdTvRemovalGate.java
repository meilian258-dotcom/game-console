package cn.piq.fcarcade.home;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class WideLcdTvRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private WideLcdTvRemovalGate() {}

    static Result attempt(WideLcdTvFootprint.Facing facing, int clickedPart,
                          Predicate<WideLcdTvFootprint.Cell> loaded,
                          Predicate<WideLcdTvFootprint.Cell> permitted,
                          Predicate<WideLcdTvFootprint.Cell> owned,
                          Predicate<WideLcdTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        return attempt(facing, clickedPart, false, loaded, permitted, owned, breakEvent, originalUnchanged, commit);
    }

    static Result attempt(WideLcdTvFootprint.Facing facing, int clickedPart, boolean centered,
                          Predicate<WideLcdTvFootprint.Cell> loaded,
                          Predicate<WideLcdTvFootprint.Cell> permitted,
                          Predicate<WideLcdTvFootprint.Cell> owned,
                          Predicate<WideLcdTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        var cells = WideLcdTvFootprint.cells(facing, centered);
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
