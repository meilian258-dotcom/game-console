// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDependencyManifestTest {
    private static final String HASH = "A".repeat(64);
    private static final String VALID = "abi=2\nwindows-x64.sha256=" + HASH
            + "\nruntime-dependency-api=1\nwindows-x64.runtime.count=1\n"
            + "windows-x64.runtime.0.name=libc++.dll\nwindows-x64.runtime.0.sha256=" + HASH
            + "\nwindows-x64.runtime.0.bytes=1659392\n";
    private static RuntimeDependencyManifest.Bundle read(String value) throws IOException {
        return RuntimeDependencyManifest.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.ISO_8859_1)));
    }
    @Test void singleFixedDependency() throws Exception {
        var bundle = read(VALID);
        assertEquals(HASH.toLowerCase(), bundle.bridgeSha256());
        assertEquals("libc++.dll", bundle.dependencies().getFirst().name());
        assertEquals(1659392, bundle.dependencies().getFirst().bytes());
        assertThrows(UnsupportedOperationException.class, () -> bundle.dependencies().clear());
    }
    @Test void twoDependenciesInFixedOrder() throws Exception {
        String two = VALID.replace("count=1", "count=2").replace("0.name=libc++.dll", "0.name=libunwind.dll")
                + "windows-x64.runtime.1.name=libc++.dll\nwindows-x64.runtime.1.sha256=" + HASH
                + "\nwindows-x64.runtime.1.bytes=1659392\n";
        assertEquals(2, read(two).dependencies().size());
        assertThrows(IOException.class, () -> read(two.replace("0.name=libunwind.dll", "0.name=libc++.dll")));
    }
    @Test void noSilentLegacyOrCapabilityFallback() {
        for (String value : new String[]{VALID.replace("runtime-dependency-api=1\n", ""),
                VALID.replace("runtime-dependency-api=1", "runtime-dependency-api=0"),
                VALID.replace("abi=2", "abi=1"), VALID.replace("count=1", "count=0"),
                VALID.replace("count=1", "count=3"), VALID.replace("count=1", "count=01")})
            assertThrows(IOException.class, () -> read(value));
    }
    @Test void duplicateUnknownAndMissingPropertiesFail() {
        for (String value : new String[]{VALID + "abi=2\n", VALID + "windows-x64.runtime.1.name=libc++.dll\n",
                VALID + "other=true\n", VALID.replace("windows-x64.runtime.0.bytes=1659392\n", ""),
                VALID + "a\\u0062i=2\n"}) assertThrows(IOException.class, () -> read(value));
    }
    @Test void noPathsOrUnlistedNames() {
        for (String name : new String[]{"../libc++.dll", "C:/libc++.dll", "libcxx.dll", "LIBC++.DLL",
                "libunwind.dll", "other.dll", ""})
            assertThrows(IOException.class, () -> read(VALID.replace("0.name=libc++.dll", "0.name=" + name)));
    }
    @Test void boundedLengthsHashesAndManifest() {
        for (String size : new String[]{"0", "-1", "16777217", "999999999999999999", "01", "1.0", ""})
            assertThrows(IOException.class, () -> read(VALID.replace("0.bytes=1659392", "0.bytes=" + size)));
        assertThrows(IOException.class, () -> read(VALID.replace(HASH, "Z".repeat(64))));
        assertThrows(IOException.class, () -> read(VALID.replace(HASH, "a".repeat(63))));
        assertThrows(IOException.class, () -> read(VALID + "#".repeat(16384)));
        assertThrows(IOException.class, () -> read(VALID + "bad=\\uQQQQ\n"));
    }
}
