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
    @Test void contentCardCoverScanRetainsValidImagesAndReportsEachRejectedFile() throws Exception {
        byte[] good=CartridgeCoverCodecTest.label();String hash=RomRepository.sha256(good);
        var repository=new CartridgeCoverRepository(root);repository.store(hash,good);
        var bad=root.resolve("a".repeat(64)+".png");Files.write(bad,new byte[]{1,2,3});
        Files.createDirectory(root.resolve("b".repeat(64)+".png"));
        Files.write(root.resolve("not-a-hash.png"),good);
        var scan=repository.scan();assertEquals(1,scan.entries().size());assertEquals(hash,scan.entries().getFirst().hash());
        assertEquals(3,scan.failures().size());assertTrue(scan.failures().stream().anyMatch(f->f.name().equals("not-a-hash.png")));
        assertArrayEquals(good,repository.read(hash));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(bad));
        assertThrows(IOException.class,()->repository.read("a".repeat(64)));
    }
    @Test void localCoverScanChecksPngAndIsolatesInvalidFilesWithoutUploading() throws Exception {
        byte[] good=CartridgeCoverCodecTest.label();Files.write(root.resolve("my-cover.png"),good);Files.write(root.resolve("bad.png"),new byte[]{1,2,3});
        var local=new cn.piq.fcarcade.home.content.ContentCardStore(root,java.util.Set.of("png"),bytes->CartridgeCoverCodec.prepare(bytes),CartridgeLimits.MAX_SOURCE_COVER_BYTES);
        var scan=local.scan();assertEquals(1,scan.entries().size());assertEquals("my-cover.png",scan.entries().getFirst().name());
        assertEquals(1,scan.failures().size());assertEquals("bad.png",scan.failures().getFirst().name());
        try(var files=Files.list(root)){assertEquals(2,files.count());}
        assertArrayEquals(good,Files.readAllBytes(root.resolve("my-cover.png")));
    }
    @Test void contentCardCoverScanStillRejectsDirectoryOverBudget() throws Exception {
        for(int i=0;i<257;i++)Files.write(root.resolve(i+".txt"),new byte[]{1});
        assertThrows(IOException.class,()->new CartridgeCoverRepository(root).scan());
    }
    @Test void unsupportedCoverFilesAreReportedWithoutChangingLegacyCatalog() throws Exception {
        Files.write(root.resolve("cover.zip"),new byte[]{1});
        var repository=new CartridgeCoverRepository(root);var scan=repository.scan();
        assertTrue(scan.entries().isEmpty());assertEquals(1,scan.failures().size());assertTrue(scan.failures().getFirst().reason().contains("未扫描：扩展名不支持（支持 .png）"));
        assertTrue(repository.list().isEmpty());
    }
}
