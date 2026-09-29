package cn.piq.fcarcade.home;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class PanelTvRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private PanelTvRemovalGate() {}

    static Result attempt(PanelTvFootprint.Facing facing, int clickedPart,
                          Predicate<PanelTvFootprint.Cell> loaded,
                          Predicate<PanelTvFootprint.Cell> permitted,
                          Predicate<PanelTvFootprint.Cell> owned,
                          Predicate<PanelTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        return attempt(facing, clickedPart, false, loaded, permitted, owned, breakEvent, originalUnchanged, commit);
    }

    static Result attempt(PanelTvFootprint.Facing facing, int clickedPart, boolean wide,
                          Predicate<PanelTvFootprint.Cell> loaded,
                          Predicate<PanelTvFootprint.Cell> permitted,
                          Predicate<PanelTvFootprint.Cell> owned,
                          Predicate<PanelTvFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        var cells = PanelTvFootprint.cells(facing, wide);
        if(clickedPart<0||clickedPart>=cells.size())return Result.REPLACED;
        // Do not fire even an extra protection event until all occupied chunks and
        // basic permissions are available. Never use a predicate to load chunks.
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        for (var cell : cells) if (!permitted.test(cell)) return Result.DENIED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        boolean[] originalOwned=new boolean[cells.size()];for(var cell:cells)originalOwned[cell.part()]=owned.test(cell);
        for (var cell : cells) {
            if (cell.part() != clickedPart && owned.test(cell) && !breakEvent.test(cell)) return Result.DENIED;
        }
        for (var cell : cells) if (!loaded.test(cell)) return Result.UNLOADED;
        for (var cell : cells) if (!permitted.test(cell)) return Result.DENIED;
        for (var cell : cells) if (owned.test(cell)!=originalOwned[cell.part()]) return Result.REPLACED;
        if (!originalUnchanged.getAsBoolean()) return Result.REPLACED;
        commit.run();
        return Result.REMOVED;
    }
}
