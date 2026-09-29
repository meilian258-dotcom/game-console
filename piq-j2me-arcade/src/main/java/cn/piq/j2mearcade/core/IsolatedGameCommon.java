package cn.piq.j2mearcade.core;

import org.microemu.EmulatorContext;
import org.microemu.app.Common;

/**
 * Tiny Common subclass that is deliberately defined again by the per-game
 * class loader. MicroEmulator's "system" launch path asks the concrete Common
 * class for its class loader, which lets us load large legacy classes without
 * its obsolete 16 KiB MIDletClassLoader limit.
 */
public final class IsolatedGameCommon extends Common {
    public IsolatedGameCommon(EmulatorContext context) {
        super(context);
    }
}
