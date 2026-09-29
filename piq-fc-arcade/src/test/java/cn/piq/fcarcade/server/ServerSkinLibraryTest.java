package cn.piq.fcarcade.server;

import cn.piq.fcarcade.skin.SkinImageCodec;
import cn.piq.fcarcade.skin.SkinLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ServerSkinLibraryTest {
    @TempDir
    Path temporary;

    @Test
    void storesCatalogsAndReloadsPreparedSkin() throws Exception {
        BufferedImage image = new BufferedImage(
                2048,
                2048,
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF00FF00);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", source);
        SkinImageCodec.Prepared prepared =
                SkinImageCodec.prepare(source.toByteArray());

        ServerSkinLibrary library = new ServerSkinLibrary(temporary);
        library.store("绿色测试", prepared.sha256(), prepared.png());

        assertEquals(1, library.catalog().size());
        assertEquals("绿色测试", library.catalog().getFirst().name());
        ServerSkinLibrary.SkinData found =
                library.find(prepared.sha256());
        assertNotNull(found);
        assertArrayEquals(prepared.png(), found.bytes());

        ServerSkinLibrary reloaded = new ServerSkinLibrary(temporary);
        assertEquals("绿色测试", reloaded.catalog().getFirst().name());
        assertEquals(SkinLayout.ROCKET_V1_2048, reloaded.catalog().getFirst().layout());
    }

    @Test
    void oldSkinIsCataloguedAsIncompatibleWithoutChangingOrDeletingItsFile() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(100, 100, 0xFFFF0000);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", source);
        byte[] original = source.toByteArray();
        String hash = SkinImageCodec.sha256(original);
        Path path = temporary.resolve(hash + ".png");
        java.nio.file.Files.write(path, original);
        ServerSkinLibrary library = new ServerSkinLibrary(temporary);
        var descriptor = library.catalog().getFirst();
        assertEquals(SkinLayout.LEGACY_512, descriptor.layout());
        org.junit.jupiter.api.Assertions.assertFalse(descriptor.compatible());
        var found = library.find(hash);
        assertNotNull(found);
        assertArrayEquals(original, found.bytes());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> library.store("不能误应用旧皮肤", hash, original));
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(path));
        assertEquals(1, library.catalog().size());
    }
}
