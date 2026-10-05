// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.rom.LegacySfcContentPaths;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;

final class SfcContentMigrationSelfTest {
    static void verify() throws Exception {
        Path temporary = Files.createTempDirectory("piq-sfc-content-migration-");
        try {
            byte[] rom = SfcLegalTestRom.create();
            Path instance = temporary.resolve("instance");
            var content = new LegacySfcContentPaths(instance);
            require(!Files.exists(instance), "constructing paths performed IO");
            Files.createDirectories(content.legacy());
            Files.write(content.legacy().resolve("中文游戏.sfc"), rom);
            Files.writeString(content.legacy().resolve("unknown.txt"), "keep");
            Files.write(content.legacy().resolve("invalid.smc"), new byte[16]);
            var first = content.prepare();
            require(first.copied() == 1 && first.reused() == 0 && first.rejected().size() == 1, "copy/reject report wrong");
            require(Arrays.equals(rom, Files.readAllBytes(content.root().resolve("中文游戏.sfc"))), "copied bytes changed");
            require(Files.exists(content.legacy().resolve("中文游戏.sfc")) && Files.exists(content.legacy().resolve("unknown.txt"))
                    && Files.exists(content.legacy().resolve("invalid.smc")), "migration removed an original");
            require(!Files.exists(content.root().resolve("invalid.smc")) && !Files.exists(content.root().resolve("unknown.txt")), "unaccepted file imported");
            var second = content.prepare();
            require(second.copied() == 0 && second.reused() == 1, "retry duplicated content");
            require(new SfcRomRepository(content.root()).find(SfcRomImage.fromBytes(rom).sha256()) != null, "new repository cannot read migrated ROM");

            var conflict = new LegacySfcContentPaths(temporary.resolve("conflict"));
            Files.createDirectories(conflict.legacy()); Files.createDirectories(conflict.root());
            Files.write(conflict.legacy().resolve("a.sfc"), rom);
            Files.write(conflict.legacy().resolve("z.sfc"), rom);
            byte[] changed = rom.clone(); changed[0] ^= 1;
            Files.write(conflict.root().resolve("z.sfc"), changed);
            expectIo(conflict::prepare);
            require(!Files.exists(conflict.root().resolve("a.sfc")), "conflict was found only after publishing partial files");
            require(Arrays.equals(changed, Files.readAllBytes(conflict.root().resolve("z.sfc"))), "conflict overwrote destination");

            var overLimit = new LegacySfcContentPaths(temporary.resolve("limit"));
            Files.createDirectories(overLimit.legacy());
            for (int i = 0; i < 513; i++) Files.createFile(overLimit.legacy().resolve("ignored-" + i + ".txt"));
            expectIo(overLimit::prepare);
            try (var entries = Files.list(overLimit.root())) { require(entries.findAny().isEmpty(), "over-limit import published files"); }

            var cancelled = new LegacySfcContentPaths(temporary.resolve("cancelled"));
            Files.createDirectories(cancelled.legacy()); Files.write(cancelled.legacy().resolve("game.sfc"), rom);
            Thread.currentThread().interrupt();
            try { expectIo(cancelled::prepare); } finally { Thread.interrupted(); }
            require(!Files.exists(cancelled.root().resolve("game.sfc")), "cancelled import wrote a ROM");

            Path outside = temporary.resolve("outside"); Files.createDirectory(outside);
            var linked = new LegacySfcContentPaths(temporary.resolve("linked")); Files.createDirectories(linked.legacy());
            boolean linksSupported = true;
            try { Files.createSymbolicLink(linked.legacy().resolve("redirect.sfc"), outside); }
            catch (UnsupportedOperationException | FileSystemException unavailable) { linksSupported = false; }
            if (linksSupported) expectIo(linked::prepare);
            else System.out.println("SFC symlink probe skipped: host cannot create symbolic links");
            System.out.println("SFC copy-only content migration self-test passed");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void expectIo(Action action) throws Exception {
        try { action.run(); throw new AssertionError("expected migration failure"); } catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
