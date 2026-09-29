package cn.piq.retro.storage;
import java.io.UncheckedIOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ConsoleStorageTest {
    @TempDir Path base;
    @Test void copiesVerifiesAndArchivesWithoutDeletingOriginalBytes()throws Exception{
        Path old=base.resolve("piq-pvz/saves/player/game");Files.createDirectories(old);Files.writeString(old.resolve("save.dat"),"progress");
        Path root=ConsoleStorage.root(base);assertEquals(base.resolve("game-console"),root);assertEquals("progress",Files.readString(root.resolve("piq-pvz/saves/player/game/save.dat")));assertFalse(Files.exists(base.resolve("piq-pvz")));
        try(var stream=Files.walk(root.resolve("legacy-backup"))){assertEquals(1,stream.filter(p->p.getFileName().toString().equals("save.dat")).count());}
        assertEquals(root,ConsoleStorage.root(base));
    }
    @Test void conflictsPreserveBothCopiesAndPreventSilentEmptySave()throws Exception{
        Files.createDirectories(base.resolve("piq-pvz"));Files.writeString(base.resolve("piq-pvz/save"),"old");Files.createDirectories(base.resolve("game-console/piq-pvz"));Files.writeString(base.resolve("game-console/piq-pvz/save"),"new");
        assertThrows(UncheckedIOException.class,()->ConsoleStorage.root(base));assertEquals("old",Files.readString(base.resolve("piq-pvz/save")));assertEquals("new",Files.readString(base.resolve("game-console/piq-pvz/save")));
    }
    @Test void equalPartialCopyResumesAndLeavesMinecraftDirectoriesAlone()throws Exception{
        for(String dir:new String[]{"piq-fc","game-console/piq-fc","config","mods","natives-windows-x86_64"})Files.createDirectories(base.resolve(dir));
        Files.writeString(base.resolve("piq-fc/save"),"same");Files.writeString(base.resolve("game-console/piq-fc/save"),"same");Files.writeString(base.resolve("piq-fc/missing"),"copy");
        ConsoleStorage.root(base);assertEquals("copy",Files.readString(base.resolve("game-console/piq-fc/missing")));for(String dir:new String[]{"config","mods","natives-windows-x86_64"})assertTrue(Files.isDirectory(base.resolve(dir)));
    }
    @Test void rebindsOwnedFilesOnlyAndKeepsWorldScopesSeparate()throws Exception{
        Path game=base.resolve("piq-computer/games/main.pak");Files.createDirectories(game.getParent());Files.writeString(game,"game");
        assertEquals(base.resolve("game-console/piq-computer/games/main.pak"),ConsoleStorage.rebind(base,game));assertEquals(base.resolve("external/main.pak"),ConsoleStorage.rebind(base,base.resolve("external/main.pak")));
        for(String world:new String[]{"world1","world2"}){Path root=base.resolve(world);Files.createDirectories(root.resolve("piq-fc"));Files.writeString(root.resolve("piq-fc/save"),world);assertEquals(world,Files.readString(ConsoleStorage.root(root).resolve("piq-fc/save")));}
    }
    @Test void staleLegacyFilesCannotResurrectAfterMigrationMarker()throws Exception{
        Files.createDirectories(base.resolve("piq-fc"));Files.createDirectories(base.resolve("game-console/.migration"));Files.writeString(base.resolve("game-console/.migration/piq-fc.done"),"previous migration");
        assertThrows(UncheckedIOException.class,()->ConsoleStorage.root(base));assertTrue(Files.isDirectory(base.resolve("piq-fc")));
    }
}
