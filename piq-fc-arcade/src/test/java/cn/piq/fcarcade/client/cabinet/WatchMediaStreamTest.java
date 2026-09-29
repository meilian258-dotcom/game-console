package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaCodec;
import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.retro.api.RetroFrame;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchMediaStreamTest {
    @Test void publicAddonBridgeTransmitsVideoAndRawPcmWithoutAnyEmulator() throws Exception {
        UUID source = UUID.randomUUID(), host = UUID.randomUUID();
        int[] pixels = new int[256 * 224]; Arrays.fill(pixels, 0xff12aa67);
        short[] pcm = {100, -100, 2000, -2000, 32000, -32000};
        RetroFrame input = new RetroFrame(256, 224, pixels, 4F/3F, 0, pcm);
        try (var sender = new WatchMediaStream(source, host, true);
             var viewer = new WatchMediaStream(source, host, false)) {
            sender.sending(true); sender.offer(input);
            RetroFrame frame = null; short[] heard = null;
            long end = System.nanoTime() + 3_000_000_000L;
            while (System.nanoTime() < end && (frame == null || heard == null)) {
                List<CabinetMediaPacket> batch;
                while ((batch = sender.pollOutbound()) != null) batch.forEach(viewer::accept);
                RetroFrame next = viewer.pollVideo(); if (next != null) frame = next;
                short[] nextAudio = viewer.pollAudio(); if (nextAudio != null) heard = nextAudio;
                Thread.sleep(5);
            }
            assertNotNull(frame); assertArrayEquals(pcm, heard);
            assertArrayEquals(CabinetMediaCodec.decodeVideo(CabinetMediaCodec.encodeVideo(input)), frame.abgr());
            assertEquals(input.displayAspect(), frame.displayAspect());
            assertNull(sender.error()); assertNull(viewer.error());
        }
    }
    @Test void noDemandProducesNoPackets() throws Exception {
        try (var sender = new WatchMediaStream(UUID.randomUUID(), UUID.randomUUID(), true)) {
            sender.offer(new RetroFrame(1, 1, new int[]{-1}, 1, 0, new short[]{1, -1}));
            Thread.sleep(60); assertNull(sender.pollOutbound());
        }
    }
}
