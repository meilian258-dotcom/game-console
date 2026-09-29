package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalArcadePreferencesTest {
    @Test void handSizeDefaultAndExplicitSavePreserveOtherLocalSettings()throws IOException {
        Path path=temporary.resolve("hands.properties");Files.writeString(path,"other=keep\ncontrollerHandSize=NaN\n");
        var prefs=new LocalArcadePreferences(path);prefs.loadOnce();assertEquals(1.18,prefs.controllerHandSize());
        assertTrue(Files.readString(path).contains("NaN"));
        prefs.setControllerHandSize(1.25);prefs.save();var reloaded=new LocalArcadePreferences(path);reloaded.loadOnce();
        assertEquals(1.25,reloaded.controllerHandSize());assertTrue(Files.readString(path).contains("other=keep"));
        for(double bad:new double[]{.9,1.31,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->prefs.setControllerHandSize(bad));
    }
    @Test void screenAspectDefaultsWithoutWritingAndPersistsWithOtherSettings() throws IOException {
        Path path=temporary.resolve("aspect.properties");
        Files.writeString(path,"maxSimulatedSpectators=6\notherSetting=keep\n");
        var prefs=new LocalArcadePreferences(path);prefs.loadOnce();
        assertEquals(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE,prefs.dualScreenAspect());
        prefs.setDualScreenAspect(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.SQUARE);prefs.save();
        var reloaded=new LocalArcadePreferences(path);reloaded.loadOnce();
        assertEquals(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.SQUARE,reloaded.dualScreenAspect());
        assertEquals(6,reloaded.maximumSpectators());
        assertTrue(Files.readString(path).contains("otherSetting=keep"));
    }
    @Test void invalidAspectDoesNotRewriteTheUsersFile() throws IOException {
        Path path=temporary.resolve("invalid-aspect.properties");
        String original="dualScreenAspect=NaN\nmaxSimulatedSpectators=3\n";
        Files.writeString(path,original);var prefs=new LocalArcadePreferences(path);prefs.loadOnce();
        assertEquals(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.FOUR_THREE,prefs.dualScreenAspect());
        assertEquals(original,Files.readString(path));
        assertEquals(3,prefs.maximumSpectators());
    }
    @Test void changingAspectSurvivesSaveFailureOnlyInMemory() throws IOException {
        Path blocker=temporary.resolve("aspect-blocker");Files.writeString(blocker,"keep");
        var prefs=new LocalArcadePreferences(blocker.resolve("prefs"));prefs.loadOnce();
        prefs.setDualScreenAspect(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.WIDE);
        assertThrows(IOException.class,prefs::save);
        assertEquals(cn.piq.fcarcade.layout.ScreenAspectFit.Aspect.WIDE,prefs.dualScreenAspect());
        assertEquals("keep",Files.readString(blocker));
    }
    @TempDir
    Path temporary;

    @Test
    void missingFileDefaultsToTwoWithoutCreatingConfig() throws IOException {
        Path path = temporary.resolve(LocalArcadePreferences.FILE_NAME);
        LocalArcadePreferences preferences = new LocalArcadePreferences(path);
        preferences.loadOnce();
        assertEquals(2, preferences.maximumSpectators());
        assertFalse(Files.exists(path));
    }

    @Test
    void readsOnlyOnceAndExplicitSavePersistsZero() throws IOException {
        Path path = temporary.resolve(LocalArcadePreferences.FILE_NAME);
        Files.writeString(path, "maxSimulatedSpectators=6\notherSetting=keep\n");
        LocalArcadePreferences preferences = new LocalArcadePreferences(path);
        preferences.loadOnce();
        assertEquals(6, preferences.maximumSpectators());
        Files.writeString(path, "maxSimulatedSpectators=8\n");
        preferences.loadOnce();
        assertEquals(6, preferences.maximumSpectators());
        preferences.setMaximumSpectators(0);
        preferences.save();

        LocalArcadePreferences reloaded = new LocalArcadePreferences(path);
        reloaded.loadOnce();
        assertEquals(0, reloaded.maximumSpectators());
        assertTrue(Files.readString(path).contains("otherSetting=keep"));
    }

    @Test
    void invalidValuesDefaultWithoutOverwritingExistingFile() throws IOException {
        for (String invalid : new String[]{"-1", "9", "unlimited", "2147483648"}) {
            Path path = temporary.resolve("invalid-" + invalid + ".properties");
            String original = "maxSimulatedSpectators=" + invalid + "\n";
            Files.writeString(path, original);
            LocalArcadePreferences preferences = new LocalArcadePreferences(path);
            preferences.loadOnce();
            assertEquals(2, preferences.maximumSpectators());
            assertEquals(original, Files.readString(path));
        }
    }

    @Test
    void explicitChangeIsInMemoryBeforeWritingAndSurvivesSaveFailure() throws IOException {
        Path blockingFile = temporary.resolve("not-a-directory");
        Files.writeString(blockingFile, "preserve");
        LocalArcadePreferences preferences = new LocalArcadePreferences(
                blockingFile.resolve(LocalArcadePreferences.FILE_NAME));
        preferences.loadOnce();
        preferences.setMaximumSpectators(0);
        assertEquals(0, preferences.maximumSpectators());
        assertThrows(IOException.class, preferences::save);
        assertEquals(0, preferences.maximumSpectators());
        assertEquals("preserve", Files.readString(blockingFile));
    }

    @Test
    void boundsAreEnforcedAndMaximumCanRoundTrip() throws IOException {
        Path path = temporary.resolve("config").resolve(LocalArcadePreferences.FILE_NAME);
        LocalArcadePreferences preferences = new LocalArcadePreferences(path);
        preferences.loadOnce();
        assertThrows(IllegalArgumentException.class, () -> preferences.setMaximumSpectators(-1));
        assertThrows(IllegalArgumentException.class, () -> preferences.setMaximumSpectators(9));
        preferences.setMaximumSpectators(8);
        preferences.save();
        LocalArcadePreferences reloaded = new LocalArcadePreferences(path);
        reloaded.loadOnce();
        assertEquals(8, reloaded.maximumSpectators());
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void malformedFileFailureIsNotRetriedEveryFrame() throws IOException {
        Path path = temporary.resolve(LocalArcadePreferences.FILE_NAME);
        Files.writeString(path, "maxSimulatedSpectators=\\uZZZZ\n");
        LocalArcadePreferences preferences = new LocalArcadePreferences(path);
        assertThrows(IOException.class, preferences::loadOnce);
        Files.writeString(path, "maxSimulatedSpectators=8\n");
        preferences.loadOnce();
        assertEquals(2, preferences.maximumSpectators());
    }

    @Test
    void gameDirectoryFactoryCopiesExistingPreferencesThenWritesOnlyNewLocation() throws IOException {
        Path old = temporary.resolve("config").resolve(LocalArcadePreferences.FILE_NAME);
        Files.createDirectories(old.getParent());
        Files.writeString(old, "maxSimulatedSpectators=6\notherSetting=keep\n");
        var preferences = LocalArcadePreferences.forGameDirectory(temporary);
        assertFalse(Files.exists(temporary.resolve("piq-fc")));
        preferences.loadOnce();
        assertEquals(6, preferences.maximumSpectators());
        preferences.setMaximumSpectators(0);
        preferences.save();
        assertTrue(Files.readString(old).contains("maxSimulatedSpectators=6"));
        var reloaded = LocalArcadePreferences.forGameDirectory(temporary);
        reloaded.loadOnce();
        assertEquals(0, reloaded.maximumSpectators());
        assertTrue(Files.readString(temporary.resolve("game-console/piq-fc/config").resolve(LocalArcadePreferences.FILE_NAME)).contains("otherSetting=keep"));
    }

    @Test
    void failedConfigMigrationCannotOverwriteDataWithDefaults() throws IOException {
        Path blocker = temporary.resolve("piq-fc");
        Files.writeString(blocker, "preserve blocker");
        var preferences = LocalArcadePreferences.forGameDirectory(temporary);
        assertThrows(IOException.class, preferences::loadOnce);
        preferences.setMaximumSpectators(0);
        assertThrows(IOException.class, preferences::save);
        preferences.loadOnce(); // Failure is not retried on a render tick.
        assertEquals("preserve blocker", Files.readString(blocker));
    }
}
