// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.input;

/** One physical controller source belongs to exactly one local session identity. */
public final class InputOwnership {
    private static Object owner;
    private InputOwnership() {}
    public static synchronized boolean acquire(Object candidate) {
        if (candidate == null) throw new IllegalArgumentException("Missing input owner");
        if (owner != null && owner != candidate) return false;
        owner = candidate; return true;
    }
    public static synchronized boolean owns(Object candidate) { return candidate != null && owner == candidate; }
    /** Read-only: observers yield to a player session without acquiring its input source. */
    public static synchronized boolean occupied() { return owner != null; }
    public static synchronized void release(Object candidate) { if (owner == candidate) owner = null; }
}
