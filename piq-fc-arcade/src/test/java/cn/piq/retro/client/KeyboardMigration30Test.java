package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardMigration30Test {
    @TempDir Path dir;
    @Test void v1CustomZXAndHotkeysArePreservedUntilExplicitRevisionSave()throws Exception{
        var c=KeyboardConfig.defaults().withHotkeys(297,296).withCustom(KeyboardConfig.Profile.NES,List.of(88,90,259,257,265,264,263,262));
        byte[] v1=new String(KeyboardConfigStore.encode(c),StandardCharsets.UTF_8).replace("version=3","version=1").getBytes(StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("config"));Path file=KeyboardConfigStore.path(dir);Files.write(file,v1);
        var loaded=KeyboardConfigStore.load(dir);assertEquals(297,loaded.config().toggleKey());
        assertEquals(KeyboardConfig.Preset.CUSTOM,loaded.config().bindings(KeyboardConfig.Profile.NES).preset());
        assertEquals(88,loaded.config().bindings(KeyboardConfig.Profile.NES).customKeys().get(0));
        assertEquals(90,loaded.config().bindings(KeyboardConfig.Profile.NES).customKeys().get(1));
        assertFalse(loaded.warning().isBlank());assertArrayEquals(v1,Files.readAllBytes(file));
        KeyboardConfigStore.save(dir,loaded.config(),loaded.revision());
        assertTrue(Files.readString(file).contains("version=3"));assertEquals("",KeyboardConfigStore.load(dir).warning());
        assertFalse(Files.exists(dir.resolve("options.txt")));
    }
    @Test void overlayPreservesAliasesMouseAndExtrasWithoutCollision(){
        int[][] raw={{75,90},{90},{-1000},{-1},{265}};int[] extras={296,74};
        var keys=KeyboardConfig.effectiveLegacyKeys(KeyboardConfig.Profile.NES,raw,extras,90,296);
        var extra=KeyboardConfig.effectiveLegacyExtras(KeyboardConfig.Profile.NES,raw,extras,90,296);
        assertArrayEquals(new int[]{75},keys[0]);assertArrayEquals(new int[]{76},keys[1]);
        assertArrayEquals(new int[]{-1000},keys[2]);assertArrayEquals(new int[]{-1},keys[3]);
        assertArrayEquals(new int[]{73,74},extra);assertArrayEquals(new int[]{75,90},raw[0]);assertArrayEquals(new int[]{296,74},extras);
        keys[0][0]=1;assertEquals(75,raw[0][0]);
    }
    @Test void allFixedSchemesHaveNoMovementKeyAsFunctionAndNoHotkey(){
        for(var p:KeyboardConfig.Profile.values())for(var preset:List.of(KeyboardConfig.Preset.CLASSIC,KeyboardConfig.Preset.NUMPAD,KeyboardConfig.Preset.WASD)){
            var keys=KeyboardConfig.presetKeys(p,preset);assertFalse(keys.contains(78));assertFalse(keys.contains(296));
            for(int i=0;i<keys.size();i++)if(i<4||i>7)assertFalse(Set.of(87,83,65,68,340,341,32).contains(keys.get(i)));
        }
    }
    @Test void explicitCurrentHotkeysRemainUserControlled()throws Exception{
        var c=KeyboardConfig.defaults().withHotkeys(298,299);assertEquals(c,KeyboardConfigStore.decode(KeyboardConfigStore.encode(c)));
    }
}
