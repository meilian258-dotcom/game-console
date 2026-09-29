package cn.piq.fcarcade.home;

import java.util.UUID;

/** Pure value policy, with object identity supplied by the authoritative inventory owner. */
public record CartridgeEditBinding(UUID token, UUID cartridgeId, int hand, int slot) {
    public CartridgeEditBinding {
        if (token == null || cartridgeId == null || hand < 0 || hand > 1
                || (hand == 0 ? slot < 0 || slot > 8 : slot != 40))
            throw new IllegalArgumentException("卡带手位或槽位无效");
    }
    public boolean permits(CartridgeEditBinding claimed, UUID heldId, int currentSlot,
                           boolean sameStackObject, boolean alive, boolean authorized) {
        return equals(claimed) && cartridgeId.equals(heldId) && slot == currentSlot
                && sameStackObject && alive && authorized;
    }
}
