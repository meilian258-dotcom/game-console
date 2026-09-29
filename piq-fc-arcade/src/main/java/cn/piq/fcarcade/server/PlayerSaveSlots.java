package cn.piq.fcarcade.server;

import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Predicate;

final class PlayerSaveSlots {
    static final int SLOT_COUNT = 3;

    private PlayerSaveSlots() {
    }

    static String key(UUID playerId, int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            throw new IllegalArgumentException("玩家存档槽编号无效");
        }
        return "player|" + playerId + "|global-slot|" + slot;
    }

    static void migrateLegacy(
            ArcadeSaveStore store,
            UUID playerId,
            IntFunction<String> defaultName,
            Predicate<ArcadeSaveStore.SaveInfo> active,
            Predicate<String> activeTarget
    ) {
        List<ArcadeSaveStore.SaveInfo> all = store.list();
        boolean[] occupied = new boolean[SLOT_COUNT + 1];
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            String key = key(playerId, slot);
            occupied[slot] = activeTarget.test(key) || all.stream()
                    .anyMatch(save -> key.equals(save.saveKey()));
        }
        String legacyKey = "player|" + playerId;
        String oldSlotPrefix = legacyKey + "|slot|";
        List<ArcadeSaveStore.SaveInfo> candidates = all.stream()
                .filter(save -> save.saveKey().equals(legacyKey)
                        || isOldSlotKey(save.saveKey(), oldSlotPrefix))
                .filter(active.negate())
                .toList();
        int candidateIndex = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            if (occupied[slot]) continue;
            String targetKey = key(playerId, slot);
            while (candidateIndex < candidates.size()) {
                ArcadeSaveStore.SaveInfo candidate =
                        candidates.get(candidateIndex++);
                String name = candidate.slotName().isBlank()
                        ? defaultName.apply(slot)
                        : candidate.slotName();
                // The target may be reserved by a running session without a file yet.
                // Recheck after callbacks too, before migrate can delete its old source.
                if (active.test(candidate)) continue;
                if (activeTarget.test(targetKey) || store.list().stream().anyMatch(save -> targetKey.equals(save.saveKey()))) {
                    candidateIndex--;
                    break;
                }
                if (store.migrate(
                        candidate.saveKey(),
                        targetKey,
                        candidate.romSha256(),
                        name,
                        candidate.players())) {
                    occupied[slot] = true;
                    break;
                }
            }
        }
    }

    private static boolean isOldSlotKey(
            String saveKey,
            String prefix
    ) {
        if (!saveKey.startsWith(prefix)) return false;
        String suffix = saveKey.substring(prefix.length());
        return suffix.equals("1")
                || suffix.equals("2")
                || suffix.equals("3");
    }
}
