// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The real pre-commit removal gate, with world/event access supplied by the caller. */
final class NativeCabinetRemovalGate {
    enum Result { REMOVED, UNLOADED, DENIED, REPLACED }

    private NativeCabinetRemovalGate() {}

    static Result attempt(NativeCabinetFootprint.Facing facing, int clickedPart,
                          Predicate<NativeCabinetFootprint.Cell> loaded,
                          Predicate<NativeCabinetFootprint.Cell> permitted,
                          Predicate<NativeCabinetFootprint.Cell> owned,
                          Predicate<NativeCabinetFootprint.Cell> breakEvent,
                          BooleanSupplier originalUnchanged, Runnable commit) {
        if (clickedPart < 0 || clickedPart >= NativeCabinetFootprint.CELL_COUNT) return Result.REPLACED;
        var cells = NativeCabinetFootprint.cells(facing);
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
