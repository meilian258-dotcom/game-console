package cn.piq.fcarcade.home;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class HomeTvRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private HomeTvRemovalGate() {}

    static Result attempt(HomeTvFootprint.Facing facing, int clickedPart,
                          Predicate<HomeTvFootprint.Cell> loaded,
                          Predicate<HomeTvFootprint.Cell> permitted,
                          Predicate<HomeTvFootprint.Cell> owned,
                          Predicate<HomeTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        return attempt(facing, clickedPart, false, loaded, permitted, owned, breakEvent, originalUnchanged, commit);
    }

    static Result attempt(HomeTvFootprint.Facing facing, int clickedPart, boolean centered,
                          Predicate<HomeTvFootprint.Cell> loaded,
                          Predicate<HomeTvFootprint.Cell> permitted,
                          Predicate<HomeTvFootprint.Cell> owned,
                          Predicate<HomeTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        var cells = HomeTvFootprint.cells(facing, centered);
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
