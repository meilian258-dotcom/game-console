package cn.piq.fcarcade.client;

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

final class ArcadeKeyMappings {
    private static final String CATEGORY = "key.categories.piq_fc_arcade";
    private static final IKeyConflictContext ARCADE_CONTEXT = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return ClientArcadeEvents.isControlling()
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
    static final KeyMapping A = mapping("a", GLFW.GLFW_KEY_K);
    static final KeyMapping A_ALT = mapping("a_alt", GLFW.GLFW_KEY_X);
    static final KeyMapping B = mapping("b", GLFW.GLFW_KEY_J);
    static final KeyMapping B_ALT = mapping("b_alt", GLFW.GLFW_KEY_Z);
    static final KeyMapping START = mapping("start", GLFW.GLFW_KEY_ENTER);
    static final KeyMapping SELECT = mapping("select", GLFW.GLFW_KEY_RIGHT_SHIFT);
    static final KeyMapping SELECT_ALT = mapping("select_alt", GLFW.GLFW_KEY_BACKSPACE);
    // Reset is intentionally unbound by default so it never steals TaCZ's reload key.
    static final KeyMapping RESET = mapping("reset", GLFW.GLFW_KEY_UNKNOWN);
    static final KeyMapping MUTE = mapping("mute", GLFW.GLFW_KEY_M);

    private static final List<KeyMapping> ALL = List.of(
            UP, DOWN, LEFT, RIGHT,
            A, A_ALT, B, B_ALT,
            START, SELECT, SELECT_ALT, RESET, MUTE);
    private static final Map<KeyMapping, InputConstants.Key> SUPPRESSED =
            new IdentityHashMap<>();
    private static final String RECOVERY_FILE =
            "piq-fc-arcade-suppressed-keys.properties";
    private static boolean legacyBindingsChecked;
    private static boolean recoveryChecked;
    private static boolean suppressionReady;

    private ArcadeKeyMappings() {
    }

    static void register(RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
        cn.piq.retro.client.KeyboardInput.registerLegacy(cn.piq.retro.client.KeyboardConfig.Profile.NES,ArcadeKeyMappings::legacyKeys);
        cn.piq.retro.client.KeyboardInput.registerLegacyExtras(cn.piq.retro.client.KeyboardConfig.Profile.NES,
                () -> new int[]{keyboardKey(RESET),keyboardKey(MUTE)});
    }

    static int[][] legacyKeys(){return new int[][]{{keyboardKey(A),keyboardKey(A_ALT)},{keyboardKey(B),keyboardKey(B_ALT)},
            {keyboardKey(SELECT),keyboardKey(SELECT_ALT)},{keyboardKey(START)},{keyboardKey(UP)},{keyboardKey(DOWN)},{keyboardKey(LEFT)},{keyboardKey(RIGHT)}};}
    private static int keyboardKey(KeyMapping mapping){return cn.piq.retro.client.KeyboardInput.legacyKey(mapping);}

    static void syncOtherMappings() {
        recoverInterruptedSuppression();
        // New control uses a pre-vanilla key gate, never temporarily rebinds other mods.
        restoreOtherMappings();
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
            // Keep the journal for a later launch rather than discarding recovery data.
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
                saved.store(output, "PIQ FC temporary key bindings");
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

    private static void migrateLegacyBindings() {
        if (legacyBindingsChecked) return;
        legacyBindingsChecked = true;
        if (!A.getKey().equals(key(GLFW.GLFW_KEY_J))
                || !A_ALT.getKey().equals(key(GLFW.GLFW_KEY_Z))
                || !B.getKey().equals(key(GLFW.GLFW_KEY_K))
                || !B_ALT.getKey().equals(key(GLFW.GLFW_KEY_X))) {
            return;
        }
        A.setKey(key(GLFW.GLFW_KEY_K));
        A_ALT.setKey(key(GLFW.GLFW_KEY_X));
        B.setKey(key(GLFW.GLFW_KEY_J));
        B_ALT.setKey(key(GLFW.GLFW_KEY_Z));
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
    }

    private static void restoreOtherMappings() {
        if (SUPPRESSED.isEmpty()) {
            suppressionReady = false;
            return;
        }
        SUPPRESSED.forEach((mapping, key) -> {
            mapping.setKey(key);
            mapping.setDown(false);
            while (mapping.consumeClick()) {
                // Do not replay input collected while the arcade owned this key.
            }
        });
        SUPPRESSED.clear();
        suppressionReady = false;
        KeyMapping.resetMapping();
        Minecraft.getInstance().options.save();
        try {
            Files.deleteIfExists(recoveryPath());
        } catch (IOException ignored) {
            // A stale journal is safe: recovery only fills mappings still unbound.
        }
    }

    static void restoreAll() {
        restoreOtherMappings();
        for (KeyMapping arcade : ALL) {
            arcade.setDown(false);
        }
    }

    private static KeyMapping mapping(String name, int defaultKey) {
        return new KeyMapping(
                "key.piq_fc_arcade." + name,
                ARCADE_CONTEXT,
                InputConstants.Type.KEYSYM,
                defaultKey,
                CATEGORY);
    }

    private static InputConstants.Key key(int keyCode) {
        return InputConstants.Type.KEYSYM.getOrCreate(keyCode);
    }
}
