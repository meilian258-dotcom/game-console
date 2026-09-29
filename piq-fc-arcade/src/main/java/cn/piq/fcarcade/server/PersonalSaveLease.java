package cn.piq.fcarcade.server;

import java.util.UUID;

/** The controller may change; authorization to overwrite a personal slot never transfers. */
final class PersonalSaveLease {
    private final UUID owner;
    private boolean sealed;

    PersonalSaveLease(UUID owner) {
        this.owner = owner;
    }

    boolean writable() {
        return !sealed;
    }

    boolean sealOnDeparture(UUID player) {
        if (sealed || !owner.equals(player)) return false;
        sealed = true;
        return true;
    }

    boolean owns(UUID player) {
        return owner.equals(player);
    }

    /** Callers also use this gate for deletes: a restart must not delete a sealed save. */
    boolean write(Runnable persistence) {
        if (sealed) return false;
        persistence.run();
        return true;
    }
}
