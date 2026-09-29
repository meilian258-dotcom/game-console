package cn.piq.fcarcade.home;

import java.util.UUID;

/** Snapshot of the server-synchronized main hand, not an editor permission token. */
public record CartridgeAssemblyBinding(UUID requestId, int slot, UUID cartridgeId, long revision) {
    public static final UUID ZERO = new UUID(0, 0);
    public CartridgeAssemblyBinding {
        if (requestId == null || requestId.equals(ZERO) || cartridgeId == null || slot < 0 || slot > 8 || revision < 0)
            throw new IllegalArgumentException("拆卡请求无效");
    }
    public boolean permits(int selected, UUID heldId, long heldRevision, int count,
                           boolean whole, boolean shift, boolean alive, boolean inventoryOnly) {
        return !cartridgeId.equals(ZERO) && cartridgeId.equals(heldId) && revision == heldRevision
                && slot == selected && count == 1 && whole && shift && alive && inventoryOnly;
    }
}
