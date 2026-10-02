package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.ContentCardDirectories;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContentCardDirectoriesTest {
    @TempDir Path root;
    @Test void machineAndPurposeDirectoriesAreSeparateWithoutCreatingFiles(){
        var md=ResourceLocation.fromNamespaceAndPath("piq_md_home","md");
        var gba=ResourceLocation.fromNamespaceAndPath("piq_gba","gba");
        var roms=ContentCardDirectories.roms(root,md);var covers=ContentCardDirectories.covers(root,md);
        assertEquals(root.resolve("game-console/content-cards/piq_md_home/md"),roms);
        assertEquals(root.resolve("game-console/content-card-covers/piq_md_home/md"),covers);
        assertNotEquals(covers,ContentCardDirectories.covers(root,gba));assertFalse(covers.startsWith(roms));
        assertFalse(java.nio.file.Files.exists(roms));assertFalse(java.nio.file.Files.exists(covers));
        var metadata=ContentCardDirectories.metadata(root,md);
        assertEquals(root.resolve("game-console/content-card-metadata/piq_md_home/md"),metadata);
        assertFalse(metadata.startsWith(roms));assertFalse(metadata.startsWith(covers));assertFalse(java.nio.file.Files.exists(metadata));
        assertNotEquals(metadata,ContentCardDirectories.metadata(root,gba));
    }
    @Test void systemPathCannotEscapeItsNamespace(){
        assertThrows(IllegalArgumentException.class,()->ContentCardDirectories.covers(root,ResourceLocation.fromNamespaceAndPath("piq_md_home","../../outside")));
    }
}
