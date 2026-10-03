// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import cn.piq.fcarcade.rom.RomRepository;
import cn.piq.sfcarcade.core.SfcRomImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SfcServerContentPathsTest {
    @TempDir Path temp;

    private SfcServerContentPaths.Location location(String world, SfcServerContentPaths.Area area) {
        return SfcServerContentPaths.location(temp.resolve("server"), temp.resolve("server").resolve(world), area);
    }
    private static void write(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent()); Files.write(path, data);
    }
    private static byte[] rom(int value) { byte[] bytes = new byte[32768]; Arrays.fill(bytes, (byte) value); return bytes; }

    @Test void worldScopeIsRelativePortableAndDistinctWithoutCreatingDirectories() {
        Path a = temp.resolve("computer-a/instance"), b = temp.resolve("computer-b/renamed-instance");
        String first = SfcServerContentPaths.scope(a, a.resolve("saves/中文 世界"));
        assertEquals(first, SfcServerContentPaths.scope(b, b.resolve("saves/中文 世界")));
        assertNotEquals(first, SfcServerContentPaths.scope(a, a.resolve("saves/另一个世界")));
        assertNotEquals(SfcServerContentPaths.scope(a, a.resolve("world")), SfcServerContentPaths.scope(a, a.resolve("backup/world")));
        assertEquals(SfcServerContentPaths.scope(a, a), SfcServerContentPaths.scope(b, b));
        var paths = SfcServerContentPaths.location(a, a.resolve("world"), SfcServerContentPaths.Area.ROMS);
        assertTrue(paths.root().startsWith(a.resolve("game-console/world-content")));
        assertEquals(a.resolve("world/game-console/piq-sfc-home/roms"), paths.legacy());
        assertFalse(Files.exists(a)); assertFalse(Files.exists(b));
    }

    @Test void crossDriveWindowsLocationConstructionIsSafeAndIoFailsBeforeAnyDiskAccess() {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        // No Files call or directory setup: neither configured drive needs to exist.
        Path server=Path.of("X:\\sfc-no-touch-server"), world=Path.of("Y:\\sfc-no-touch-world");
        var roms=assertDoesNotThrow(()->SfcServerContentPaths.location(server,world,SfcServerContentPaths.Area.ROMS));
        var covers=assertDoesNotThrow(()->SfcServerContentPaths.location(server,world,SfcServerContentPaths.Area.COVERS));
        var romStore=assertDoesNotThrow(()->new SfcRomStore(roms));
        var coverStore=assertDoesNotThrow(()->new SfcCoverStore(covers));
        for(int i=0;i<2;i++) {
            IOException romError=assertThrows(IOException.class,romStore::list);
            IOException coverError=assertThrows(IOException.class,coverStore::list);
            assertTrue(romError.getMessage().contains("未读取或创建内容目录"));
            assertTrue(coverError.getMessage().contains("未读取或创建内容目录"));
            assertInstanceOf(IllegalArgumentException.class,romError.getCause());
            assertInstanceOf(IllegalArgumentException.class,coverError.getCause());
        }
    }

    @Test void oldRomListAndReadImportBytesWithoutTouchingSavesOrUnknownFiles() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.ROMS);
        byte[] bytes = rom(0); Path source = paths.legacy().resolve("中文 游戏.sfc"); write(source, bytes);
        Path save = paths.legacy().getParent().resolve("saves/player.bin"); write(save, new byte[]{7,8});
        Path unknown = paths.legacy().resolve("notes.txt"); write(unknown, new byte[]{4});
        Path oldStaging = paths.legacy().resolve(".hosted-old/game.sfc"); write(oldStaging, rom(1));
        var store = new SfcRomStore(paths); var entries = store.list();
        assertEquals(1, entries.size()); assertEquals("中文 游戏.sfc", entries.getFirst().fileName());
        String hash = SfcRomImage.fromBytes(bytes).sha256(); assertArrayEquals(bytes, store.read(hash));
        assertArrayEquals(bytes, Files.readAllBytes(source)); assertArrayEquals(bytes, Files.readAllBytes(paths.root().resolve(source.getFileName())));
        assertArrayEquals(new byte[]{7,8}, Files.readAllBytes(save)); assertTrue(Files.exists(unknown)); assertTrue(Files.exists(oldStaging));
        assertFalse(Files.exists(paths.root().resolve("notes.txt"))); assertFalse(Files.exists(paths.root().resolve(".hosted-old")));
        assertFalse(Files.exists(paths.root().getParent().resolve("saves")));
    }

    @Test void normalizedSfcIdentityDoesNotChangeCopiedSmcBytes() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.ROMS);
        byte[] payload = rom(0), withHeader = new byte[payload.length + 512];
        Arrays.fill(withHeader, 0, 512, (byte) 19); System.arraycopy(payload,0,withHeader,512,payload.length);
        Path old = paths.legacy().resolve("header.smc"); write(old, withHeader);
        var store = new SfcRomStore(paths); String hash = SfcRomImage.fromBytes(withHeader).sha256();
        assertEquals(SfcRomImage.fromBytes(payload).sha256(), hash);
        assertArrayEquals(payload, store.read(hash));
        assertArrayEquals(withHeader, Files.readAllBytes(paths.root().resolve("header.smc")));
        assertArrayEquals(withHeader, Files.readAllBytes(old));
    }

    @Test void twoWorldsCannotListOrReadEachOthersMigratedContent() throws Exception {
        var a = location("world-a", SfcServerContentPaths.Area.ROMS); var b = location("world-b", SfcServerContentPaths.Area.ROMS);
        write(a.legacy().resolve("same-name.sfc"), rom(0)); write(b.legacy().resolve("same-name.sfc"), rom(1));
        var first = new SfcRomStore(a); var second = new SfcRomStore(b);
        String hashA = first.list().getFirst().sha256(), hashB = second.list().getFirst().sha256();
        assertNotEquals(hashA, hashB); assertThrows(IOException.class, () -> second.read(hashA));
        assertThrows(IOException.class, () -> first.read(hashB)); assertArrayEquals(rom(0), first.read(hashA));
    }

    @Test void sameFileImportsAreIdempotentAcrossStoresAndThreads() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.ROMS); write(paths.legacy().resolve("game.sfc"), rom(0));
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i=0;i<8;i++) futures.add(executor.submit(() -> {
                try { location("world", SfcServerContentPaths.Area.ROMS).prepare(); }
                catch (IOException failure) { throw new CompletionException(failure); }
            }));
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertArrayEquals(rom(0), Files.readAllBytes(paths.root().resolve("game.sfc")));
        try(var files=Files.list(paths.root())) { assertEquals(List.of("game.sfc"), files.map(p->p.getFileName().toString()).toList()); }
        assertArrayEquals(rom(0), Files.readAllBytes(paths.legacy().resolve("game.sfc")));
    }

    @Test void differingTargetRejectsBeforePublishingEarlierMissingFileAndCanRetry() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.ROMS);
        write(paths.legacy().resolve("a.sfc"), rom(0)); write(paths.legacy().resolve("z.sfc"), rom(1));
        Path conflict = paths.root().resolve("z.sfc"); write(conflict, rom(2));
        assertThrows(IOException.class, paths::prepare); assertFalse(Files.exists(paths.root().resolve("a.sfc")));
        assertArrayEquals(rom(2), Files.readAllBytes(conflict)); assertArrayEquals(rom(1), Files.readAllBytes(paths.legacy().resolve("z.sfc")));
        Files.move(conflict, temp.resolve("recoverable-conflicting-file.sfc"));
        paths.prepare(); assertArrayEquals(rom(0),Files.readAllBytes(paths.root().resolve("a.sfc")));
        assertArrayEquals(rom(1),Files.readAllBytes(conflict));
    }

    @Test void onlyNewDirectoryReceivesUploadsAndNoMissingLegacyTreeIsCreated() throws Exception {
        var paths = location("new-world", SfcServerContentPaths.Area.ROMS); var store = new SfcRomStore(paths);
        byte[] data = rom(0); String hash = SfcRomImage.fromBytes(data).sha256(); store.store("new.sfc", hash, data);
        assertTrue(Files.exists(paths.root().resolve(hash+".sfc"))); assertFalse(Files.exists(paths.legacy()));
        assertFalse(Files.exists(temp.resolve("server/new-world"))); assertArrayEquals(data, store.read(hash));
    }

    @Test void preparedLocationDoesNotTurnOldDirectoryIntoASecondWriteOrRefreshSource() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.ROMS); write(paths.legacy().resolve("initial.sfc"),rom(0));
        var store = new SfcRomStore(paths); assertEquals(1, store.list().size());
        write(paths.legacy().resolve("late.sfc"), rom(1)); assertEquals(1, store.list().size());
        assertFalse(Files.exists(paths.root().resolve("late.sfc"))); assertTrue(Files.exists(paths.legacy().resolve("late.sfc")));
    }

    @Test void coverListReadAndNewWritesUseWorldScopedRoot() throws Exception {
        var paths = location("world", SfcServerContentPaths.Area.COVERS);
        BufferedImage image = new BufferedImage(512,256,BufferedImage.TYPE_INT_RGB); byte[] png;
        try (var output = new ByteArrayOutputStream()) { assertTrue(ImageIO.write(image,"png",output)); png=output.toByteArray(); }
        finally { image.flush(); }
        String hash=RomRepository.sha256(png); Path old=paths.legacy().resolve(hash+".png"); write(old,png);
        var store = new SfcCoverStore(paths); assertEquals(List.of(hash),store.list()); assertArrayEquals(png,store.read(hash));
        assertArrayEquals(png,Files.readAllBytes(old)); assertArrayEquals(png,Files.readAllBytes(paths.root().resolve(hash+".png")));
        var other=location("other-world",SfcServerContentPaths.Area.COVERS);var isolated=new SfcCoverStore(other);
        assertTrue(isolated.list().isEmpty());assertThrows(IOException.class,()->isolated.read(hash));
        isolated.store(hash,png);assertTrue(Files.exists(other.root().resolve(hash+".png")));assertFalse(Files.exists(other.legacy()));
    }

    @Test void mixedGoodEmptyShortAndOversizedLegacyRomsKeepValidListReadAndWriteWorking() throws Exception {
        var paths=location("mixed",SfcServerContentPaths.Area.ROMS);
        byte[] valid=rom(0);write(paths.legacy().resolve("good.sfc"),valid);
        write(paths.legacy().resolve("empty.sfc"),new byte[0]);write(paths.legacy().resolve("short.sfc"),new byte[]{1,2,3});
        Path oversized=paths.legacy().resolve("oversized.sfc");
        try(var out=FileChannel.open(oversized,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            out.position(SfcServerContentPaths.Area.ROMS.maxFileBytes);out.write(ByteBuffer.wrap(new byte[]{1}));
        }
        var store=new SfcRomStore(paths);var entries=store.list();
        assertEquals(List.of("good.sfc"),entries.stream().map(e->e.fileName()).toList());
        assertArrayEquals(valid,store.read(SfcRomImage.fromBytes(valid).sha256()));
        assertEquals(Set.of("empty.sfc","short.sfc","oversized.sfc"),paths.rejectedFiles().stream().map(r->r.source().getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(paths.rejectedFiles().stream().allMatch(r->r.reason().contains("文件大小")));
        for(String name:List.of("empty.sfc","short.sfc","oversized.sfc")){assertTrue(Files.exists(paths.legacy().resolve(name)));assertFalse(Files.exists(paths.root().resolve(name)));}
        assertEquals(0,Files.size(paths.legacy().resolve("empty.sfc")));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(paths.legacy().resolve("short.sfc")));
        assertEquals(SfcServerContentPaths.Area.ROMS.maxFileBytes+1,Files.size(oversized));
        byte[] uploaded=rom(1);String hash=SfcRomImage.fromBytes(uploaded).sha256();store.store("upload.sfc",hash,uploaded);
        assertArrayEquals(uploaded,store.read(hash));assertEquals(2,store.list().size());
        assertFalse(Files.exists(paths.legacy().resolve(hash+".sfc")));
    }

    @Test void invalidCoverFormatIsReportedAndLeftBehindWithoutBlockingValidCover() throws Exception {
        var paths=location("bad-cover",SfcServerContentPaths.Area.COVERS);
        BufferedImage image=new BufferedImage(512,256,BufferedImage.TYPE_INT_RGB);byte[] png;
        try(var output=new ByteArrayOutputStream()){assertTrue(ImageIO.write(image,"png",output));png=output.toByteArray();}finally{image.flush();}
        String valid=RomRepository.sha256(png);write(paths.legacy().resolve(valid+".png"),png);
        Path broken=paths.legacy().resolve("a".repeat(64)+".png");write(broken,new byte[]{1,2,3});
        var store=new SfcCoverStore(paths);assertEquals(List.of(valid),store.list());assertArrayEquals(png,store.read(valid));
        assertEquals(1,paths.rejectedFiles().size());assertTrue(paths.rejectedFiles().getFirst().reason().contains("内容格式校验失败"));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(broken));assertFalse(Files.exists(paths.root().resolve(broken.getFileName())));
    }

    @Test void tooManyValidLegacyFilesStillRefuseBeforePublishing() throws Exception {
        var many=location("many",SfcServerContentPaths.Area.ROMS);
        for(int i=0;i<257;i++)write(many.legacy().resolve("game-"+i+".sfc"),rom(0));
        assertThrows(IOException.class,many::prepare);
        try(var files=Files.list(many.root())){assertEquals(0,files.count());}
    }

    @Test void rejectedInvalidSourceCannotSilentlyHideADestinationConflict() throws Exception {
        var paths=location("invalid-conflict",SfcServerContentPaths.Area.ROMS);
        write(paths.legacy().resolve("empty.sfc"),new byte[0]);write(paths.root().resolve("empty.sfc"),rom(0));
        IOException failure=assertThrows(IOException.class,paths::prepare);assertTrue(failure.getMessage().contains("同名"));
        assertEquals(0,Files.size(paths.legacy().resolve("empty.sfc")));assertArrayEquals(rom(0),Files.readAllBytes(paths.root().resolve("empty.sfc")));
    }

    @Test void rejectsSourceOrDestinationSymlinksAndNeverCopiesExternalBytes() throws Exception {
        var source=location("source-link",SfcServerContentPaths.Area.ROMS);Path outside=temp.resolve("outside.sfc");write(outside,rom(0));
        Files.createDirectories(source.legacy());Path link=source.legacy().resolve("game.sfc");
        try{Files.createSymbolicLink(link,outside);}catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"Symbolic links unavailable: "+unavailable);}
        assertThrows(IOException.class,source::prepare);assertFalse(Files.exists(source.root().resolve("game.sfc")));
        var destination=location("destination-link",SfcServerContentPaths.Area.ROMS);write(destination.legacy().resolve("game.sfc"),rom(1));
        Files.createDirectories(destination.root());Files.createSymbolicLink(destination.root().resolve("game.sfc"),outside);
        assertThrows(IOException.class,destination::prepare);assertArrayEquals(rom(0),Files.readAllBytes(outside));
    }

    @Test void rejectsDirectoryJunctionOnWindows() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        var paths=location("junction",SfcServerContentPaths.Area.ROMS);Path actual=temp.resolve("actual-content");write(actual.resolve("game.sfc"),rom(0));
        Files.createDirectories(paths.legacy().getParent());
        Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",paths.legacy().toString(),actual.toString()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        assumeTrue(process.waitFor(10,TimeUnit.SECONDS)&&process.exitValue()==0,"Junction unavailable: "+output);
        try{assertThrows(IOException.class,paths::prepare);assertArrayEquals(rom(0),Files.readAllBytes(actual.resolve("game.sfc")));}
        finally{Files.delete(paths.legacy());}
    }

    @Test void everySfcServerContentEntryUsesResolverButSaveRootStaysInWorld() throws Exception {
        Path source=Path.of("src/main/java/cn/piq/sfchome/server");
        for(String file:List.of("SfcCartridgeEditorService.java","SfcCoverService.java","SfcHomeServer.java")){
            String text=Files.readString(source.resolve(file));assertTrue(text.contains("SfcServerContentPaths.location("),file);
            assertFalse(text.contains("resolve(\"piq-sfc-home/roms\")"),file);assertFalse(text.contains("resolve(\"piq-sfc-home/covers\")"),file);
        }
        String server=Files.readString(source.resolve("SfcHomeServer.java"));
        assertTrue(server.contains("ConsoleStorage.root(world).resolve(\"piq-sfc-home/hosted-saves\")"));
        String hosted=Files.readString(source.resolve("SfcHostedWorker.java"));assertTrue(hosted.contains("new SfcRomStore(library).read(rom)"));
    }
}
