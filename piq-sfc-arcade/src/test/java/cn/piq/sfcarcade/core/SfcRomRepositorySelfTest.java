// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import cn.piq.sfcarcade.rom.SfcRomEntry;
import cn.piq.sfcarcade.rom.SfcRomRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Dependency-free verification for the server/client ROM library contract. */
public final class SfcRomRepositorySelfTest {
    private SfcRomRepositorySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        cn.piq.sfcarcade.server.SfcDownloadGateSelfTest.verify();
        cn.piq.sfcarcade.server.ContentIoTaskSelfTest.verify();
        SfcContentMigrationSelfTest.verify();
        Path root = Files.createTempDirectory("piq-sfc-rom-repository-");
        try {
            SfcRomRepository repository = new SfcRomRepository(root);
            byte[] raw = SfcLegalTestRom.create();
            String sha256 = SfcRomImage.fromBytes(raw).sha256();

            require(repository.list().isEmpty(), "new repository is not empty");
            SfcRomEntry stored = repository.storeVerified("legal-test.sfc", sha256, raw);
            require(stored.sha256().equals(sha256), "stored hash changed");
            require(Arrays.equals(repository.readVerified(stored), raw),
                    "stored bytes changed");
            require(repository.find(sha256) != null, "stored ROM cannot be found by hash");

            SfcRomEntry duplicate = repository.storeVerified("legal-test.sfc", sha256, raw);
            require(duplicate.path().equals(stored.path()),
                    "same name/hash created a duplicate file");

            byte[] headered = new byte[raw.length + SfcRomImage.COPIER_HEADER_BYTES];
            System.arraycopy(raw, 0, headered, SfcRomImage.COPIER_HEADER_BYTES, raw.length);
            SfcRomEntry smc = repository.storeVerified("headered-test.smc", sha256, headered);
            require(smc.sha256().equals(sha256),
                    "copier header affected normalized hash");
            require(repository.list().size() == 2, "repository catalog size is wrong");

            expectIOException(() -> repository.storeVerified(
                    "wrong-hash.sfc", "0".repeat(64), raw));
            for (String unsafe : new String[]{"../escape.sfc", "a\\escape.sfc", "x:y.sfc", " padded.sfc ", "CON.sfc"})
                expectIOException(() -> repository.storeVerified(unsafe, sha256, raw));
            // An occupied suffix must not be overwritten, even when the requested base name also conflicts.
            byte[] changed = raw.clone(); changed[0] ^= 1;
            String otherHash = SfcRomImage.fromBytes(changed).sha256();
            Path suffix = root.resolve("legal-test-" + otherHash.substring(0, 8) + ".sfc");
            Files.write(suffix, raw);
            expectIOException(() -> repository.storeVerified("legal-test.sfc", otherHash, changed));
            require(Arrays.equals(raw, Files.readAllBytes(suffix)), "suffix collision overwrote existing ROM");
            System.out.println("SFC ROM repository self-test passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void expectIOException(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            // Expected: untrusted transfers must not enter the local repository.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
