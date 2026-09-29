package cn.piq.fcarcade.cabinet;

/** Immutable common-side admission policy, never inferred from a client's Hello packet. */
public record CabinetSyncPolicy(int maxPlayers, String expectedCompatibility) {
    public CabinetSyncPolicy {
        if (maxPlayers < 1 || maxPlayers > 4) throw new IllegalArgumentException("Invalid sync player capacity");
        if (expectedCompatibility != null) checkCompatibility(expectedCompatibility);
    }

    public boolean hostSnapshot() { return expectedCompatibility != null; }

    /** A pinned snapshot profile must also be checked for the very first host. */
    public boolean acceptsHost(Identity identity) {
        return identity != null && (!hostSnapshot() || expectedCompatibility.equals(identity.compatibility()));
    }

    /** Only an explicit pinned policy permits independent cold-boot bytes to differ.
     * This grants permission to restore, NOT permission to input or skip the restore SHA check. */
    public boolean acceptsGuest(Identity host, Identity guest) {
        return acceptsHost(host) && acceptsHost(guest)
                && host.romHash().equals(guest.romHash())
                && host.contentId().equals(guest.contentId())
                && host.compatibility().equals(guest.compatibility())
                && host.fpsMilli() == guest.fpsMilli()
                && (hostSnapshot() || host.initialHash().equals(guest.initialHash()));
    }

    public record Identity(String romHash, String contentId, String compatibility, int fpsMilli, String initialHash) {
        public Identity {
            if (!CabinetSyncState.validHash(romHash) || !CabinetSyncState.validHash(contentId)
                    || !CabinetSyncState.validHash(initialHash) || fpsMilli < 40000 || fpsMilli > 80000)
                throw new IllegalArgumentException("Invalid sync identity");
            checkCompatibility(compatibility);
        }
    }

    private static void checkCompatibility(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid sync compatibility fingerprint");
    }
}
