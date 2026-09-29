package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source/API wiring checks only: no client, server, chunks or runtime authority are created. */
class HomePrivateHardwareApiTest {
    @Test void localEntryRejectsMissingRemovedForeignOrReplacedHardware() throws Exception {
        String method = between(source("HomeHardware"), "public static boolean connected(", "/** Package-only generalization:");
        assertTrue(method.contains("Level level, BlockEntity console, HomeTvBlockEntity television"));
        assertTrue(method.contains("level == null || console == null || television == null"));
        assertTrue(method.contains("console.getLevel() != level || television.getLevel() != level"));
        assertTrue(method.contains("console.isRemoved() || television.isRemoved()"));
        assertTrue(method.contains("loadedEndpoint(level, television.getBlockPos()) == television"));
        assertTrue(method.contains("connectedEndpoint(level, television.getBlockPos()) == console"));
    }
    @Test void localEntryReusesExactPeerRangeAndBothStructureChecks() throws Exception {
        String hardware = source("HomeHardware");
        String endpoint = between(hardware, "static HomeEndpointBlockEntity connectedEndpoint(", "public static String selectedRom(");
        assertTrue(endpoint.contains("HomeTvStructure.complete(level, resolved)"));
        assertTrue(endpoint.contains("!mutual(console, tv) || !consoleComplete(console)"));
        String mutual = between(hardware, "private static boolean mutual(", "private static boolean consoleComplete(");
        assertTrue(mutual.contains("console.linkId().equals(tv.linkId())"));
        assertTrue(mutual.contains("console.hardwareId().equals(tv.peerId()) && tv.hardwareId().equals(console.peerId())"));
        assertTrue(mutual.contains("console.getBlockPos().equals(tv.consolePos()) && tv.getBlockPos().equals(console.peerPos())"));
        assertTrue(mutual.contains("HomeLinkLedger.inRange(console.endpoint(), tv.endpoint())"));
        String complete = between(hardware, "private static boolean consoleComplete(", "static HomeEndpointBlockEntity loadedEndpoint(");
        assertTrue(complete.contains("SuborStructure.complete(fc.getLevel(), fc.getBlockPos())"));
        assertTrue(complete.contains("HomeSystems.complete(external)"));
        String external = between(source("HomeSystems"), "static boolean complete(", "/** Hardware validity only;");
        assertTrue(external.contains("SYSTEMS.get(console.systemId()) == null || console.isRemoved()"));
        assertTrue(external.contains("console.isHardwareComplete()"));
        assertTrue(hardware.contains("if (pos == null || !level.hasChunkAt(pos)) return null;"));
    }
    @Test void hardwareApiCannotStartPlaybackGrantControlOrSelectPrivateMedia() throws Exception {
        String method = between(source("HomeHardware"), "public static boolean connected(", "/** Package-only generalization:");
        for (String forbidden : new String[]{"grant(", "powerOn(", "setPower(", "signal(", "selectedRom(", "validPlayback(", "romSha(", "PacketDistributor", "sendToServer", "setChanged("})
            assertFalse(method.contains(forbidden), forbidden);
        String hardware = source("HomeHardware");
        String legacy = between(hardware, "public static HomeConsoleBlockEntity connectedConsole(", "/**\n     * Read-only, loaded-hardware check");
        assertTrue(legacy.contains("endpoint instanceof HomeConsoleBlockEntity console ? console : null"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/" + name + ".java")).replace("\r\n", "\n");
    }
    private static String between(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start + " / " + end);
        return source.substring(from, to);
    }
}
