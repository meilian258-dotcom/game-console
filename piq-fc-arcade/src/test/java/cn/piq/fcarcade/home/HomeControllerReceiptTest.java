package cn.piq.fcarcade.home;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerReceiptTest {
    private static final UUID LEASE = UUID.fromString("04ae0dc3-c950-4acb-9b59-3e869bc8ee4c");
    private static final UUID CONSOLE = UUID.fromString("b055021d-73c8-4d27-88e9-7efbd8b8b1dc");
    @Test void bothIdleAndPublicPhysicalReceiptsRemainReadOnlyData() {
        for (long session : new long[]{0, 1, Long.MAX_VALUE}) for (int port = 0; port < 2; port++) {
            var receipt = new HomeControllerData.Receipt(LEASE, CONSOLE, "minecraft:overworld", session, port);
            assertEquals(LEASE, receipt.lease()); assertEquals(CONSOLE, receipt.console());
            assertEquals("minecraft:overworld", receipt.dimension());
            assertEquals(session, receipt.session()); assertEquals(port, receipt.port());
        }
    }
    @Test void malformedIdentityAndUnspecifiedSessionDoNotBecomeIdlePermission() {
        for (UUID id : new UUID[]{null, new UUID(0, 0)}) {
            assertThrows(IllegalArgumentException.class, () -> new HomeControllerData.Receipt(id, CONSOLE, "minecraft:overworld", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new HomeControllerData.Receipt(LEASE, id, "minecraft:overworld", 0, 0));
        }
        for (String dimension : new String[]{null, "", "overworld", "Minecraft:overworld", "minecraft:", "minecraft:hello world", "a:" + "x".repeat(255)})
            assertThrows(IllegalArgumentException.class, () -> new HomeControllerData.Receipt(LEASE, CONSOLE, dimension, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HomeControllerData.Receipt(LEASE, CONSOLE, "minecraft:overworld", -1, 0));
        for (int port : new int[]{-1, 2})
            assertThrows(IllegalArgumentException.class, () -> new HomeControllerData.Receipt(LEASE, CONSOLE, "minecraft:overworld", 0, port));
    }
    @Test void nbtAdapterRequiresExactTypesAndNeverGrantsOrActivatesALease() throws Exception {
        String all = Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home/HomeControllerData.java"));
        String adapter = all.substring(all.indexOf("public static Receipt receipt("), all.indexOf("/** Old alpha.3 loans"));
        assertTrue(adapter.contains("stack == null || stack.getCount() != 1 || !isController(stack)"));
        assertTrue(adapter.contains("data.contains(\"Borrowed\", net.minecraft.nbt.Tag.TAG_BYTE)"));
        assertTrue(adapter.contains("!data.getBoolean(\"Borrowed\")"));
        assertTrue(adapter.contains("!data.hasUUID(\"Lease\") || !data.hasUUID(\"Console\")"));
        assertTrue(adapter.contains("data.contains(\"Dimension\", net.minecraft.nbt.Tag.TAG_STRING)"));
        assertTrue(adapter.contains("data.contains(\"Session\", net.minecraft.nbt.Tag.TAG_LONG)"));
        assertTrue(adapter.contains("data.contains(\"Port\", net.minecraft.nbt.Tag.TAG_INT)"));
        assertTrue(adapter.contains("return value == null ? -1 : value.session()"));
        assertFalse(adapter.contains(".put"));
        assertFalse(adapter.contains(".bind("));
        assertFalse(adapter.contains("grant("));
        assertFalse(adapter.contains("activate("));
        assertFalse(adapter.contains("ServerArcadeSessions"));
    }
}
