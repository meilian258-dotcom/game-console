package cn.piq.retro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardConfigTest {
    @Test void newDefaultsUseAutomaticWasdWithNPositionLock(){var c=KeyboardConfig.defaults();assertEquals(78,c.toggleKey());assertEquals(296,c.settingsKey());for(var p:KeyboardConfig.Profile.values()){assertEquals(KeyboardConfig.Preset.WASD,c.bindings(p).preset());assertEquals(p.bits,c.bindings(p).customKeys().size());}}
    @Test void customSurvivesPresetChangesAndDoesNotTouchOtherProfiles(){var base=KeyboardConfig.defaults();var custom=List.of(49,50,51,52,53,54,55,56);var c=base.withCustom(KeyboardConfig.Profile.NES,custom).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.NUMPAD).withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.CUSTOM);assertEquals(custom,c.bindings(KeyboardConfig.Profile.NES).customKeys());assertEquals(base.bindings(KeyboardConfig.Profile.SFC),c.bindings(KeyboardConfig.Profile.SFC));assertEquals(base.bindings(KeyboardConfig.Profile.ARCADE),c.bindings(KeyboardConfig.Profile.ARCADE));}
    @Test void arraysAndMapsAreImmutable(){var c=KeyboardConfig.defaults();assertThrows(UnsupportedOperationException.class,()->c.profiles().clear());assertThrows(UnsupportedOperationException.class,()->c.bindings(KeyboardConfig.Profile.NES).customKeys().set(0,49));}
    @Test void actualGlfwCodesOnlyEscAndUnknownHolesRejected(){for(int key:new int[]{-1000,-2,0,33,58,60,64,94,160,255,256,270,289,315,337,349})assertFalse(KeyboardConfig.validKey(key),"key "+key);for(int key:new int[]{-1,32,39,44,57,59,61,65,93,96,161,162,257,269,280,284,290,314,320,336,340,348})assertTrue(KeyboardConfig.validKey(key),"key "+key);}
    @Test void duplicateCustomKeysRejectedButUnboundCanRepeat(){var c=KeyboardConfig.defaults();assertThrows(IllegalArgumentException.class,()->c.withCustom(KeyboardConfig.Profile.NES,List.of(49,49,51,52,53,54,55,56)));assertDoesNotThrow(()->c.withCustom(KeyboardConfig.Profile.NES,Collections.nCopies(8,-1)));}
    @Test void hotkeyConflictsAndEscAreRejected(){var c=KeyboardConfig.defaults();assertThrows(IllegalArgumentException.class,()->c.withHotkeys(296,296));assertThrows(IllegalArgumentException.class,()->c.withHotkeys(256,296));assertThrows(IllegalArgumentException.class,()->c.withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.WASD).withHotkeys(75,296));assertThrows(IllegalArgumentException.class,()->c.withCustom(KeyboardConfig.Profile.NES,List.of(78,50,51,52,53,54,55,56)));}
    @Test void presetCountsAndSixButtonSemantics(){assertEquals(List.of(322,321,259,257,265,264,263,262),KeyboardConfig.presetKeys(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.NUMPAD));assertEquals(List.of(74,76,259,257,87,83,65,68,75,73,79,80),KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.WASD));assertEquals(List.of(74,75,259,257,87,83,65,68,76,73,79,80),KeyboardConfig.presetKeys(KeyboardConfig.Profile.ARCADE,KeyboardConfig.Preset.WASD));}
    @Test void arcadeNumpadButtonsAreSequentialAndLegacyRemainsFrozen(){assertEquals(List.of(321,322,259,257,265,264,263,262,323,324,325,326),KeyboardConfig.presetKeys(KeyboardConfig.Profile.ARCADE,KeyboardConfig.Preset.NUMPAD));assertEquals(List.of(74,85,259,257,265,264,263,262,75,73,79,80),KeyboardConfig.presetKeys(KeyboardConfig.Profile.ARCADE,KeyboardConfig.Preset.LEGACY));}
    @Test void liveLegacyPrimaryAlternateAndExtraConflictsAreRecognized(){var c=KeyboardConfig.defaults().withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY).withPreset(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.LEGACY);var keys=Map.of(KeyboardConfig.Profile.NES,new int[][]{{75,297},{296}});assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c,keys,Map.of()));assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c,Map.of(),Map.of(KeyboardConfig.Profile.NES,new int[]{297})));assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c,Map.of(),Map.of(KeyboardConfig.Profile.SFC,new int[]{296})));}
    @Test void inactiveLegacyBindingsDoNotBlockDifferentActivePreset(){var c=KeyboardConfig.defaults().withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.NUMPAD);assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c,Map.of(KeyboardConfig.Profile.NES,new int[][]{{297,296}}),Map.of(KeyboardConfig.Profile.NES,new int[]{297})));}
    @Test void liveRemappingCanResolveConflictWithoutEditingOptions(){var c=KeyboardConfig.defaults().withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.LEGACY);assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c,Map.of(KeyboardConfig.Profile.NES,new int[][]{{297}}),Map.of()));assertEquals(0,KeyboardConfig.legacyHotkeyConflicts(c.withHotkeys(298,299),Map.of(KeyboardConfig.Profile.NES,new int[][]{{297}}),Map.of()));}
    @Test void directionSchemePreservesNativeOrderAndAvoidsNAndMovementFunctionKeys(){
        assertEquals(List.of(75,74,259,257,265,264,263,262),KeyboardConfig.presetKeys(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.CLASSIC));
        assertEquals(List.of(74,76,259,257,265,264,263,262,75,73,79,80),KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.CLASSIC));
        assertEquals(List.of(74,75,259,257,265,264,263,262,76,73,79,80),KeyboardConfig.presetKeys(KeyboardConfig.Profile.ARCADE,KeyboardConfig.Preset.CLASSIC));
    }
    @Test void existingVersionOnePresetsDoNotChangeToNewDefault()throws Exception{
        for(var preset:List.of(KeyboardConfig.Preset.LEGACY,KeyboardConfig.Preset.NUMPAD,KeyboardConfig.Preset.WASD,KeyboardConfig.Preset.CUSTOM)){
            var c=KeyboardConfig.defaults();for(var p:KeyboardConfig.Profile.values())c=c.withPreset(p,preset);
            assertEquals(c,KeyboardConfigStore.decode(KeyboardConfigStore.encode(c)));
        }
    }
    @Test void everyFixedPresetHasUniqueKeysAndValidNativeWidth(){
        for(var profile:KeyboardConfig.Profile.values())for(var preset:KeyboardConfig.Preset.values()){
            var keys=KeyboardConfig.presetKeys(profile,preset);
            assertEquals(profile.bits,keys.size());assertEquals(keys.size(),new HashSet<>(keys).size());
            keys.forEach(key->assertTrue(KeyboardConfig.validKey(key)));
        }
    }
    @Test void codecRoundTripPreservesThreeDistinctProfiles()throws Exception{var c=KeyboardConfig.defaults().withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.NUMPAD).withPreset(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.WASD).withCustom(KeyboardConfig.Profile.ARCADE,Collections.nCopies(12,-1));assertEquals(c,KeyboardConfigStore.decode(KeyboardConfigStore.encode(c)));}
    @Test void futureVersionIsNeverSilentlyReadAsCurrent(){assertThrows(IllegalArgumentException.class,()->KeyboardConfigStore.decode("version=99".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void missingReadDoesNotCreateConfigOrOptions()throws Exception{try(var t=new Temp()){var loaded=KeyboardConfigStore.load(t.path);assertEquals("missing",loaded.revision());assertFalse(Files.exists(t.path.resolve("config")));assertFalse(Files.exists(t.path.resolve("options.txt")));}}
    @Test void saveReloadAndRevisionRejectsAnotherWriter()throws Exception{try(var t=new Temp()){var original=KeyboardConfigStore.load(t.path);var c=original.config().withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.NUMPAD);var saved=KeyboardConfigStore.save(t.path,c,original.revision());assertEquals(c,KeyboardConfigStore.load(t.path).config());byte[] unchanged=Files.readAllBytes(KeyboardConfigStore.path(t.path));assertThrows(IOException.class,()->KeyboardConfigStore.save(t.path,original.config(),original.revision()));assertArrayEquals(unchanged,Files.readAllBytes(KeyboardConfigStore.path(t.path)));assertEquals(saved.revision(),KeyboardConfigStore.load(t.path).revision());assertFalse(Files.exists(t.path.resolve("options.txt")));}}
    @Test void invalidConfigLoadIsReadOnlyAndExplicitSaveCanRepair()throws Exception{try(var t=new Temp()){Files.createDirectory(t.path.resolve("config"));var p=KeyboardConfigStore.path(t.path);byte[] bad="version=broken".getBytes();Files.write(p,bad);var loaded=KeyboardConfigStore.load(t.path);assertFalse(loaded.warning().isEmpty());assertEquals(KeyboardConfig.defaults(),loaded.config());assertArrayEquals(bad,Files.readAllBytes(p));KeyboardConfigStore.save(t.path,loaded.config(),loaded.revision());assertEquals("",KeyboardConfigStore.load(t.path).warning());}}
    @Test void oversizedConfigFailsWithoutRewriting()throws Exception{try(var t=new Temp()){Files.createDirectory(t.path.resolve("config"));var p=KeyboardConfigStore.path(t.path);byte[] tooBig=new byte[65537];Files.write(p,tooBig);assertThrows(IOException.class,()->KeyboardConfigStore.load(t.path));assertEquals(65537,Files.size(p));}}
    @Test void directoryMasqueradingAsConfigIsRejected()throws Exception{try(var t=new Temp()){Files.createDirectories(t.path.resolve("config/piq-keyboard.properties"));assertThrows(IOException.class,()->KeyboardConfigStore.load(t.path));}}
    @Test void fileMasqueradingAsGameDirectoryIsRejected()throws Exception{try(var t=new Temp()){Path p=t.path.resolve("not-game");Files.writeString(p,"test");assertThrows(IOException.class,()->KeyboardConfigStore.load(p));}}
    private static final class Temp implements AutoCloseable {final Path path=Files.createTempDirectory("piq-keyboard-test-");Temp()throws IOException{}public void close()throws IOException{try(var paths=Files.walk(path)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}}
}
