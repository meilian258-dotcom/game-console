package cn.piq.fcarcade.home;

import java.util.List;
import java.util.function.Predicate;

/** Server-thread two-slot swap. Never uses add/drop helpers or creative-mode exceptions. */
public final class CartridgeAssemblyInventory {
    private CartridgeAssemblyInventory() {}
    public static <T> boolean split(List<T> slots, int selected, T expected, T board, T shell, Predicate<T> empty) {
        if (selected < 0 || selected > 8 || selected >= slots.size() || slots.get(selected) != expected
                || board == shell || board == expected || shell == expected
                || empty.test(expected) || empty.test(board) || empty.test(shell)) return false;
        int free = -1;
        for (int slot = 0; slot < Math.min(36, slots.size()); slot++)
            if (slot != selected && empty.test(slots.get(slot))) { free = slot; break; }
        if (free < 0) return false;
        T previous = slots.get(free);
        slots.set(selected, board);
        try { slots.set(free, shell); }
        catch (RuntimeException failure) { slots.set(selected, expected); slots.set(free, previous); throw failure; }
        return true;
    }
    public static <T> boolean combine(List<T> slots, List<T> offhand, int selected, T board, T shell,
                                      T whole, T emptyValue, Predicate<T> empty) {
        if (selected < 0 || selected > 8 || selected >= slots.size() || offhand.size() != 1
                || slots.get(selected) != board || offhand.get(0) != shell || board == shell
                || whole == board || whole == shell
                || empty.test(board) || empty.test(shell) || empty.test(whole)) return false;
        offhand.set(0, emptyValue);
        try { slots.set(selected, whole); }
        catch (RuntimeException failure) { offhand.set(0, shell); slots.set(selected, board); throw failure; }
        return true;
    }
}
