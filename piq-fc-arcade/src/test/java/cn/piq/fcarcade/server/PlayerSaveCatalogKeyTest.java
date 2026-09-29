package cn.piq.fcarcade.server;

import cn.piq.fcarcade.session.NesCoreVariant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSaveCatalogKeyTest {
    private static final UUID OWNER = UUID.fromString("12345678-1234-5678-90ab-123456789abc");
    private static final String PLAYER = "player|" + OWNER;
    private static final String[] MODES = {"", "player-home-v1|", "server-home-v1|"};
    @TempDir Path directory;

    @Test void recognizesEveryModeCoreAndCurrentSlotWithoutChangingThePlayerKey() {
        for (String mode : MODES) for (NesCoreVariant core : NesCoreVariant.values()) {
            for (int slot = 1; slot <= PlayerSaveSlots.SLOT_COUNT; slot++) {
                String player = PlayerSaveSlots.key(OWNER, slot);
                String key = mode + core.saveKey(player);
                assertEquals(player, PlayerSaveCatalogKey.normalize(key), key);
                assertEquals(OWNER, UUID.fromString(PlayerSaveCatalogKey.normalize(key).split("\\|")[1]));
            }
        }
    }

    @Test void keepsOriginalAndOldNumberedPersonalSavesVisible() {
        for (String mode : MODES) for (NesCoreVariant core : NesCoreVariant.values()) {
            assertEquals(PLAYER, PlayerSaveCatalogKey.normalize(mode + core.saveKey(PLAYER)));
            for (int slot = 1; slot <= 3; slot++) {
                String old = PLAYER + "|slot|" + slot;
                assertEquals(old, PlayerSaveCatalogKey.normalize(mode + core.saveKey(old)));
            }
        }
    }

    @Test void keepsHistoricalHostQualifiedLightGunMachineSaveVisible() {
        String old = PLAYER + "|minecraft:overworld|-549,5,-1499|LOCKSTEP";
        assertEquals(old, PlayerSaveCatalogKey.normalize(NesCoreVariant.ZAPPER_V1.saveKey(old)));
        String customDimension = PLAYER + "|example:rooms/floor_2|0,-64,2147483647|LOCKSTEP";
        assertEquals(customDimension, PlayerSaveCatalogKey.normalize(NesCoreVariant.ZAPPER_V1.saveKey(customDimension)));
    }

    @Test void rejectsRepeatedOrReorderedPrefixesInsteadOfStrippingUntilPlayerAppears() {
        String player = PlayerSaveSlots.key(OWNER, 1);
        for (String first : List.of("player-home-v1|", "server-home-v1|")) {
            for (String second : List.of("player-home-v1|", "server-home-v1|")) reject(first + second + player);
            for (NesCoreVariant core : List.of(NesCoreVariant.ZAPPER_V1, NesCoreVariant.MAPPER19_V1, NesCoreVariant.LIBRETRO_V1, NesCoreVariant.LIBRETRO_ZAPPER_V1)) {
                reject(core.saveKey(first + player));
                reject(first + core.saveKey(core.saveKey(player)));
                reject(first + core.saveKey(NesCoreVariant.ZAPPER_V1.saveKey(player)));
                reject(first + core.saveKey(NesCoreVariant.MAPPER19_V1.saveKey(player)));
                reject(first + core.saveKey(NesCoreVariant.LIBRETRO_V1.saveKey(player)));
                reject(first + core.saveKey(NesCoreVariant.LIBRETRO_ZAPPER_V1.saveKey(player)));
            }
        }
    }

    @Test void rejectsUnknownNamespacesAndMachineKeysContainingPlayerText() {
        String player = PlayerSaveSlots.key(OWNER, 1);
        reject("future-home-v1|" + player);
        reject("core|nes-legacy-v1|" + player); // Legacy never had a core prefix.
        reject("core|nes-zapper-v1/" + "0".repeat(64) + "|" + player);
        reject("core|nes-libretro-mesen-v1/" + "0".repeat(64) + "|" + player);
        reject("core|nes-libretro-mesen-zapper-v1/" + "0".repeat(64) + "|" + player);
        reject("player-home-v1|core|unknown|" + player);
        reject("minecraft:overworld|1,64,2|LOCKSTEP");
        reject("minecraft:overworld|1,64,2|LOCKSTEP|" + player);
        reject("server-home-v1|minecraft:overworld|1,64,2|LOCKSTEP|" + player);
    }

    @Test void rejectsInvalidAndAbbreviatedOwnersRatherThanMisattributingThem() {
        for (String owner : List.of("", "owner", "1-1-1-1-1", OWNER + "extra", " " + OWNER))
            reject("player-home-v1|player|" + owner + "|global-slot|1");
        reject(null);
        reject("");
        reject("player");
        String uppercase = "player|" + OWNER.toString().toUpperCase(java.util.Locale.ROOT) + "|global-slot|1";
        assertEquals(uppercase, PlayerSaveCatalogKey.normalize(uppercase));
    }

    @Test void rejectsMalformedOrUnknownSlotAndMachineSuffixes() {
        for (String suffix : List.of("|", "|global-slot|0", "|global-slot|4", "|global-slot|01",
                "|global-slot|1|extra", "|slot|-1", "|other-slot|1", "|minecraft:overworld|1,2|LOCKSTEP",
                "|minecraft:overworld|1,2,2147483648|LOCKSTEP", "|minecraft:overworld|01,2,3|LOCKSTEP",
                "|minecraft:overworld|1,2,3|UNKNOWN", "|invalid dimension|1,2,3|LOCKSTEP")) reject(PLAYER + suffix);
    }

    @Test void actualSaveStoreCatalogIncludesAllFifteenModeCoreNamespacesWithoutRewritingFiles() {
        ArcadeSaveStore store = new ArcadeSaveStore(directory);
        String rom = "a".repeat(64), player = PlayerSaveSlots.key(OWNER, 1);
        List<String> expected = new ArrayList<>();
        for (String mode : MODES) for (NesCoreVariant core : NesCoreVariant.values()) {
            String key = mode + core.saveKey(player);
            expected.add(key);
            store.save(key, rom, new byte[]{1, 2, 3});
        }
        store.save("minecraft:overworld|1,64,2|LOCKSTEP", rom, new byte[]{4});
        var before = store.list();
        var shown = before.stream().filter(save -> save.legacy()
                || PlayerSaveCatalogKey.normalize(save.saveKey()).startsWith("player|")).toList();
        assertEquals(15, shown.size());
        assertEquals(new HashSet<>(expected), new HashSet<>(shown.stream().map(ArcadeSaveStore.SaveInfo::saveKey).toList()));
        assertEquals(before, store.list());
        for (String key : expected) assertArrayEquals(new byte[]{1, 2, 3}, store.loadReadOnly(key, rom));
    }

    private static void reject(String key) {
        assertEquals("", PlayerSaveCatalogKey.normalize(key), String.valueOf(key));
    }
}
