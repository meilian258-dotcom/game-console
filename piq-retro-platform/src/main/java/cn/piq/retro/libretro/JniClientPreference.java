// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

/** Client-thread local opt-out. Server/room messages must never clear this preference. */
public final class JniClientPreference {
    private Object declinedConnection;

    public boolean enabled(Object connection, boolean connected, boolean supported) {
        return connection != null && connected && supported && connection != declinedConnection;
    }

    public void decline(Object connection) { declinedConnection = connection; }
    public void reset() { declinedConnection = null; }
}
