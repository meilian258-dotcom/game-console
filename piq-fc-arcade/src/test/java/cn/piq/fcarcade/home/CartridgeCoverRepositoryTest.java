package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeCoverRepositoryTest {
    @TempDir Path root;
    @Test void catalogContainsOnlyBoundedHashNamedRegularImagesAndChecksContentOnRead() throws Exception {
        var repository=new CartridgeCoverRepository(root);byte[] png=CartridgeCoverCodecTest.label();String hash=RomRepository.sha256(png);repository.store(hash,png);
        Files.write(root.resolve("untrusted-name.png"),png);Files.createDirectory(root.resolve("a".repeat(64)+".png"));Files.write(root.resolve("b".repeat(64)+".png"),new byte[0]);
        assertEquals(java.util.List.of(hash),repository.list());assertThrows(UnsupportedOperationException.class,()->repository.list().clear());
        Files.write(root.resolve("c".repeat(64)+".png"),new byte[]{1});assertEquals(2,repository.list().size());assertThrows(IOException.class,()->repository.read("c".repeat(64)));
    }
    @Test void catalogRejectsExcessValidNamedEntriesRatherThanSilentlyTruncating() throws Exception {
        for(int i=0;i<257;i++)Files.write(root.resolve(String.format("%064x.png",i)),new byte[]{1});
        assertThrows(IOException.class,()->new CartridgeCoverRepository(root).list());
    }
    @Test void absentCatalogIsEmptyWithoutCreatingDirectories() throws Exception {
        Path missing=root.resolve("missing");assertTrue(new CartridgeCoverRepository(missing).list().isEmpty());assertFalse(Files.exists(missing));
    }
    @Test void separateRepositoriesShareAnIdempotentWriterAndLeaveNoPartialFile() throws Exception {
        byte[] png=CartridgeCoverCodecTest.label();String hash=RomRepository.sha256(png);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<12;i++)jobs.add(workers.submit(()->{new CartridgeCoverRepository(root).store(hash,png);return null;}));
            for(var job:jobs)job.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertArrayEquals(png,new CartridgeCoverRepository(root).read(hash));
        try(var files=Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void storesVerifiedContentOnceAndReadsExactOriginalBytes() throws Exception {
        byte[] png = CartridgeCoverCodecTest.label(); String hash = RomRepository.sha256(png);
        CartridgeCoverRepository repository = new CartridgeCoverRepository(root);
        repository.store(hash, png); repository.store(hash, png);
        assertArrayEquals(png, repository.read(hash));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
    @Test void rejectsForgedHashAndNeverAcceptsArbitraryPathsOrUrls() throws Exception {
        CartridgeCoverRepository repository = new CartridgeCoverRepository(root); byte[] png = CartridgeCoverCodecTest.label();
        assertThrows(IOException.class, () -> repository.store("0".repeat(64), png));
        assertThrows(IOException.class, () -> repository.read("../private.png"));
        assertThrows(IOException.class, () -> repository.read("https://example.com/cover.png"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }
    @Test void malformedStoredBytesAreRejectedWithoutBeingOverwritten() throws Exception {
        byte[] png = CartridgeCoverCodecTest.label(); String hash = RomRepository.sha256(png);
        Path file = root.resolve(hash + ".png"); byte[] corrupted = {1, 2, 3}; Files.write(file, corrupted);
        CartridgeCoverRepository repository = new CartridgeCoverRepository(root);
        assertThrows(IOException.class, () -> repository.read(hash));
        assertThrows(IOException.class, () -> repository.store(hash, png));
        assertArrayEquals(corrupted, Files.readAllBytes(file));
    }
}
