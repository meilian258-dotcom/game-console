package cn.piq.retro.client;

import cn.piq.retro.input.GamepadState.Control;
import cn.piq.retro.input.RetroButtons.Button;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GamepadConfigStoreTest {
    @TempDir Path game;

    @Test void loadOrDraftEditsNeverCreateConfigFiles() throws Exception {
        var loaded = GamepadConfigStore.load(game);
        assertTrue(loaded.config().enabled()); assertEquals("", loaded.config().deviceKey()); assertEquals("missing", loaded.revision());
        var draft = loaded.config().enabled(false).device("0:guid");
        assertFalse(draft.enabled()); assertTrue(loaded.config().enabled());
        assertFalse(Files.exists(game.resolve("config")));
    }

    @Test void explicitSaveRoundTripsAllSystemsDeadzoneAndMultipleControls() throws Exception {
        var loaded = GamepadConfigStore.load(game);
        var config = loaded.config().enabled(true).device("1:guid").deadzone(.4f, .3f);
        config = config.profile("SFC", config.profiles().get("SFC").with(Button.A, Set.of(Control.SOUTH, Control.RIGHT_TRIGGER)).with(Button.R, Set.of()));
        var saved = GamepadConfigStore.save(game, config, loaded.revision());
        var read = GamepadConfigStore.load(game);
        assertTrue(read.config().enabled()); assertEquals("1:guid", read.config().deviceKey());
        assertEquals(.4f, read.config().deadzoneEnter()); assertEquals(.3f, read.config().deadzoneExit());
        assertEquals(config.profiles().get("SFC").bindings(), read.config().profiles().get("SFC").bindings());
        assertEquals(config.profiles().get("NES").bindings(), read.config().profiles().get("NES").bindings());
        assertEquals(saved.revision(), read.revision());
        try (var files = Files.list(game.resolve("config"))) { assertEquals(1, files.count()); }
    }

    @Test void concurrentExternalEditIsNotOverwritten() throws Exception {
        var first = GamepadConfigStore.save(game, GamepadConfig.defaults(), "missing");
        Path path = GamepadConfigStore.path(game);
        Files.writeString(path, "user change");
        assertThrows(IOException.class, () -> GamepadConfigStore.save(game, first.config().enabled(true), first.revision()));
        assertEquals("user change", Files.readString(path));
    }

    @Test void explicitlyDisabledExistingConfigurationStaysDisabled() throws Exception {
        GamepadConfigStore.save(game, GamepadConfig.defaults().enabled(false), "missing");
        assertFalse(GamepadConfigStore.load(game).config().enabled());
    }

    @Test void malformedConfigurationDisablesSafelyWithoutRepairWrites() throws Exception {
        Files.createDirectory(game.resolve("config")); Path path = GamepadConfigStore.path(game);
        for (String bad : new String[]{"enabled=true", "version=1\nenabled=maybe", "version=1\nenabled=true", "version=1\nenabled=true\nSFC.A=WRONG"}) {
            Files.writeString(path, bad);
            var read = GamepadConfigStore.load(game);
            assertFalse(read.config().enabled()); assertFalse(read.warning().isEmpty());
            assertEquals(bad, Files.readString(path));
        }
    }

    @Test void oversizedOrNonRegularConfigurationIsRejected() throws Exception {
        Files.createDirectory(game.resolve("config")); Path path = GamepadConfigStore.path(game);
        Files.write(path, new byte[65537]);
        assertThrows(IOException.class, () -> GamepadConfigStore.load(game));
        Files.delete(path); Files.createDirectory(path);
        assertThrows(IOException.class, () -> GamepadConfigStore.load(game));
    }

    @Test void symlinkConfigCannotReadOrOverwriteAnotherFile() throws Exception {
        Path external = game.resolve("outside.txt"); Files.writeString(external, "keep");
        Files.createDirectory(game.resolve("config")); Path path = game.resolve("config/piq-gamepad.properties");
        try { Files.createSymbolicLink(path, external); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) { org.junit.jupiter.api.Assumptions.abort("Windows link creation unavailable"); }
        assertThrows(IOException.class, () -> GamepadConfigStore.load(game));
        assertThrows(IOException.class, () -> GamepadConfigStore.save(game, GamepadConfig.defaults(), "missing"));
        assertEquals("keep", Files.readString(external));
    }
}
