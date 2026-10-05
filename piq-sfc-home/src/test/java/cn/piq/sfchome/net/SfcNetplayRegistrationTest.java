// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.net;

import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class SfcNetplayRegistrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void productionRegistersStartAndActivationOnceAsRequiredClientboundPlayVersion13() throws Exception {
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        var all = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);

        // The production entry point also registers hosted-media and join payloads.
        // Isolate and restore the whole SFC namespace, not just the two tested IDs.
        Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> previous = new HashMap<>();
        all.forEach((protocol, registrations) -> {
            Map<ResourceLocation, PayloadRegistration<?>> saved = new HashMap<>();
            registrations.forEach((id, registration) -> {
                if (isSfc(id)) saved.put(id, registration);
            });
            previous.put(protocol, saved);
        });
        try {
            all.values().forEach(registrations -> registrations.keySet().removeIf(SfcNetplayRegistrationTest::isSfc));
            SfcHomeNetwork.register(new RegisterPayloadHandlersEvent());

            var startId = SfcHomeNetwork.NetplayStart.TYPE.id();
            var activatedId = SfcHomeNetwork.NetplayActivated.TYPE.id();
            assertNotEquals(startId, activatedId);
            assertRegistration(all, startId);
            assertRegistration(all, activatedId);

            long wire = (1L << 50) + 32;
            UUID ticket = UUID.randomUUID();
            var session = new SfcHomeNetwork.Session(31, 2, ResourceLocation.parse("minecraft:overworld"),
                    new BlockPos(-10, 64, 4), UUID.randomUUID(), new BlockPos(-8, 64, 4),
                    UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), SfcHomeNetwork.CORE_BUILD,
                    -1, UUID.randomUUID(), true, 3, UUID.randomUUID(), UUID.randomUUID());
            // Exercise the actual registered codec, including traffic wrappers.
            roundTrip(startId, new SfcHomeNetwork.NetplayStart(session, wire, ticket));
            roundTrip(activatedId, new SfcHomeNetwork.NetplayActivated(31, 2, wire, ticket));
            assertThrows(UnsupportedOperationException.class,
                    () -> SfcHomeNetwork.register(new RegisterPayloadHandlersEvent()));
        } finally {
            all.forEach((protocol, registrations) -> {
                registrations.keySet().removeIf(SfcNetplayRegistrationTest::isSfc);
                registrations.putAll(previous.getOrDefault(protocol, Map.of()));
            });
        }
    }

    private static boolean isSfc(ResourceLocation id) {
        return id.getNamespace().equals("piq_sfc_home");
    }

    private static void assertRegistration(
            Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>> all, ResourceLocation id) {
        assertEquals(1L, all.values().stream().filter(registrations -> registrations.containsKey(id)).count(),
                () -> id + " must occur in exactly one protocol");
        var registration = all.get(ConnectionProtocol.PLAY).get(id);
        assertNotNull(registration, () -> id + " must be a PLAY payload");
        assertEquals("13", registration.version());
        assertFalse(registration.optional());
        assertEquals(PacketFlow.CLIENTBOUND, registration.flow().orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static <T> void roundTrip(ResourceLocation id, T value) {
        var codec = (StreamCodec<RegistryFriendlyByteBuf, T>) (Object)
                NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, PacketFlow.CLIENTBOUND);
        assertNotNull(codec);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, value);
            assertEquals(value, codec.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}
