// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.server;

import java.util.function.BooleanSupplier;

/** One synchronous permission transaction; callbacks cannot recursively commit another one. */
public final class InteractionTransaction {
    private final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);

    public boolean active() { return busy.get(); }

    public boolean run(BooleanSupplier facts, BooleanSupplier permission, Runnable commit) {
        if (busy.get()) return false;
        busy.set(true);
        try {
            if (!facts.getAsBoolean() || !permission.getAsBoolean() || !facts.getAsBoolean()) return false;
            commit.run();
            return true;
        } finally {
            busy.remove();
        }
    }
}
