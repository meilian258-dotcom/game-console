package cn.piq.fcarcade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FcNetworkCompatibilityTest {
    @Test
    void nativeSaveAndNetplayRequireMatchingProtocol45() {
        assertEquals("45", FcNetwork.PROTOCOL_VERSION);
    }
    @Test void gunInputDirectionAndConnectionChecksAreRegistered() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/FcNetwork.java"));
        assertTrue(source.contains("playToServer(ArcadeZapperInputPayload.TYPE,ArcadeZapperInputPayload.STREAM_CODEC"));
        assertTrue(source.contains("playToServer(ArcadeHomeInputPayload.TYPE,ArcadeHomeInputPayload.STREAM_CODEC"));
        assertTrue(source.contains("playToServer(ArcadeHomeReadyPayload.TYPE,ArcadeHomeReadyPayload.STREAM_CODEC"));
        assertTrue(source.contains("playToServer(ArcadeHomeSaveActionPayload.TYPE,ArcadeHomeSaveActionPayload.STREAM_CODEC"));
        assertTrue(source.contains("playToClient(ArcadeHomeSaveSlotsPayload.TYPE,ArcadeHomeSaveSlotsPayload.STREAM_CODEC"));
        assertTrue(source.contains("playToClient(ArcadeZapperSessionPayload.TYPE,ArcadeZapperSessionPayload.STREAM_CODEC"));
        assertTrue(source.contains("player.connection.getConnection()==source"));
        assertTrue(source.contains("source.isConnected()"));
        assertTrue(source.contains("sink.acceptsConnection(source)"));
        assertTrue(source.contains("player.getServer().getPlayerList().getPlayer(player.getUUID())==player"));
    }
    @Test void noncanonicalGunStateIsRejectedBeforeEncoding() {
        assertThrows(IllegalArgumentException.class,()->cn.piq.fcarcade.session.ZapperInput.pack(256,0,false,false));
        assertThrows(IllegalArgumentException.class,()->cn.piq.fcarcade.session.ZapperInput.pack(0,240,false,false));
        assertThrows(IllegalArgumentException.class,()->cn.piq.fcarcade.session.ZapperInput.validate(cn.piq.fcarcade.session.ZapperInput.NEUTRAL|1));
        // Payload epoch/force-release validation needs the actual MC static codec
        // runtime; ZapperWire26Probe executes those cases against the final JAR.
    }
}
