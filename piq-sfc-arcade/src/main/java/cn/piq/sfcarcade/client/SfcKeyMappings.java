// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * SFC controls and temporary conflict isolation.
 *
 * <p>Bindings from other mods are only suppressed while this client owns an SFC machine. Their
 * original values are written atomically before any binding is changed, so a crash can recover
 * bindings that Minecraft persisted as unbound.</p>
 */
final class SfcKeyMappings {
    private static final String CATEGORY = "key.categories.piq_sfc_arcade";
    private static final IKeyConflictContext SFC_CONTEXT = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return ClientSfcArcadeEvents.isControlling()
                    && Minecraft.getInstance().screen == null;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    };

    static final KeyMapping UP = mapping("up", GLFW.GLFW_KEY_UP);
    static final KeyMapping DOWN = mapping("down", GLFW.GLFW_KEY_DOWN);
    static final KeyMapping LEFT = mapping("left", GLFW.GLFW_KEY_LEFT);
    static final KeyMapping RIGHT = mapping("right", GLFW.GLFW_KEY_RIGHT);

    // U I / J K is the primary four-button diamond. The legacy A S / Z X
    // layout remains available as alternate bindings.
    static final KeyMapping A = mapping("a", GLFW.GLFW_KEY_K);
    static final KeyMapping A_ALT = mapping("a_alt", GLFW.GLFW_KEY_X);
    static final KeyMapping B = mapping("b", GLFW.GLFW_KEY_J);
    static final KeyMapping B_ALT = mapping("b_alt", GLFW.GLFW_KEY_Z);
    static final KeyMapping X = mapping("x", GLFW.GLFW_KEY_I);
    static final KeyMapping X_ALT = mapping("x_alt", GLFW.GLFW_KEY_S);
    static final KeyMapping Y = mapping("y", GLFW.GLFW_KEY_U);
    static final KeyMapping Y_ALT = mapping("y_alt", GLFW.GLFW_KEY_A);
    static final KeyMapping L = mapping("l", GLFW.GLFW_KEY_Q);
    static final KeyMapping R = mapping("r", GLFW.GLFW_KEY_E);
    static final KeyMapping START = mapping("start", GLFW.GLFW_KEY_ENTER);
    static final KeyMapping SELECT = mapping("select", GLFW.GLFW_KEY_RIGHT_SHIFT);
    static final KeyMapping SELECT_ALT = mapping("select_alt", GLFW.GLFW_KEY_BACKSPACE);

    private static final List<KeyMapping> ALL = List.of(
            UP, DOWN, LEFT, RIGHT,
            A, A_ALT, B, B_ALT,
            X, X_ALT, Y, Y_ALT,
            L, R, START, SELECT, SELECT_ALT);
    private static final Map<KeyMapping, InputConstants.Key> SUPPRESSED =
            new IdentityHashMap<>();
    private static final String RECOVERY_FILE =
            "piq-sfc-arcade-suppressed-keys.properties";
    private static boolean recoveryChecked;
    private static boolean suppressionReady;

    private SfcKeyMappings() {
    }

    static void register(RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
    }

    static void syncOtherMappings() {
        recoverInterruptedSuppression();
        if (!ClientSfcArcadeEvents.isControlling()) {
            restoreOtherMappings();
            return;
        }
        if (suppressionReady) return;

        boolean mappingChanged = false;
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (ALL.contains(other) || SUPPRESSED.containsKey(other) || other.isUnbound()) {
                continue;
            }
            boolean shared = false;
            for (KeyMapping sfc : ALL) {
                if (!sfc.isUnbound() && sfc.getKey().equals(other.getKey())) {
                    shared = true;
                    break;
                }
            }
            if (!shared) continue;
            SUPPRESSED.put(other, other.getKey());
        }

        if (!SUPPRESSED.isEmpty() && !writeRecoveryJournal()) {
            // Never risk losing another mod's binding if its original value was
            // not safely journaled first.
            SUPPRESSED.clear();
            suppressionReady = true;
            return;
        }
        for (KeyMapping other : SUPPRESSED.keySet()) {
            clearInput(other);
            other.setKey(InputConstants.UNKNOWN);
            mappingChanged = true;
        }
        if (mappingChanged) KeyMapping.resetMapping();
        suppressionReady = true;
    }

    private static void recoverInterruptedSuppression() {
        if (recoveryChecked) return;
        recoveryChecked = true;
        Path journal = recoveryPath();
        if (!Files.isRegularFile(journal)) return;

        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(journal)) {
            saved.load(input);
            boolean changed = false;
            for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
                String keyName = saved.getProperty(mapping.getName());
                if (keyName == null || !mapping.isUnbound()) continue;
                mapping.setKey(InputConstants.getKey(keyName));
                changed = true;
            }
            if (changed) {
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
            }
            Files.deleteIfExists(journal);
        } catch (IOException | RuntimeException error) {
            // Preserve the journal so a later launch can retry recovery.
        }
    }

    private static boolean writeRecoveryJournal() {
        Properties saved = new Properties();
        SUPPRESSED.forEach((mapping, key) ->
                saved.setProperty(mapping.getName(), key.getName()));
        Path journal = recoveryPath();
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        try {
            Files.createDirectories(journal.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                saved.store(output, "PIQ SFC temporary key bindings");
            }
            try {
                Files.move(temporary, journal,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort only.
            }
            return false;
        }
    }

    private static Path recoveryPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(RECOVERY_FILE);
    }

    private static void restoreOtherMappings() {
        if (SUPPRESSED.isEmpty()) {
            suppressionReady = false;
            return;
        }
        SUPPRESSED.forEach((mapping, key) -> {
            mapping.setKey(key);
            clearInput(mapping);
        });
        SUPPRESSED.clear();
        suppressionReady = false;
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        try {
            Files.deleteIfExists(recoveryPath());
        } catch (IOException ignored) {
            // A stale journal is harmless because recovery only fills unbound mappings.
        }
    }

    static void restoreAll() {
        restoreOtherMappings();
        ALL.forEach(SfcKeyMappings::clearInput);
    }

    private static void clearInput(KeyMapping mapping) {
        mapping.setDown(false);
        while (mapping.consumeClick()) {
            // Never replay an action collected while the SFC machine owned this key.
        }
    }

    private static KeyMapping mapping(String name, int defaultKey) {
        return new KeyMapping(
                "key.piq_sfc_arcade." + name,
                SFC_CONTEXT,
                InputConstants.Type.KEYSYM,
                defaultKey,
                CATEGORY);
    }
}
