package cn.piq.replayquick;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Recognize the reviewed wire extension without loading any Sable implementation classes. */
public final class SableWireContract {
    private static final String PREFIX = "dev/ryanhcode/sable/mixin/entity/entities_stick_sublevels/packet_mixin/";
    private static final Map<String, String> EXPECTED = Map.of(
            "ClientboundTeleportEntityPacketMixin", "5a702ee0bc99c0fe6a3bb549df440dee7ba5c6e707b16142f8ace84a972e2b18",
            "ClientboundMoveEntityPacketPosMixin", "65926f3b6a46e381af3e96b046fd2926133deee86765eef89ba03f701dc946fe",
            "ClientboundMoveEntityPacketPosRotMixin", "f6e1e68e5aa5f998403bf64e73cc3710e8e7e843005b895dd0355c391e783230");
    // The outside-plot equivalence proof relies on the actual decoder and container semantics,
    // not just on the three packet serializers. Reject unknown runtime implementations.
    private static final Map<String, String> SPACE_EXPECTED = Map.of(
            "dev/ryanhcode/sable/Sable.class", "5a6c80ec547d92744008b3259776dadb6a89d880393dec08d67bdd20cbfb5500",
            "dev/ryanhcode/sable/ActiveSableCompanion.class", "17b2b226a5b1c953a6070670a7d34bc150485a6f68b2ed459eb15c9b4add309a",
            "dev/ryanhcode/sable/api/sublevel/SubLevelContainer.class", "b80934d4a48fe234dfc59d5fec78d511628e6c0ab9152e8359440cf454684ad5",
            "dev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer.class", "85ea858d8b230174667d314f81d895ff801b92881b0cf3df3acf5fd27925260c",
            "dev/ryanhcode/sable/mixinterface/plot/SubLevelContainerHolder.class", "a389d14627306d1344b8001e134e8213da1736c01f3ec942407e302990b70e48",
            "dev/ryanhcode/sable/mixin/entity/entities_stick_sublevels/ClientPacketListenerMixin.class", "1e960c0d85178c900bf4201f576256457e46b7d5fba2ad970eb59a9a89c018cf");

    public static boolean supported() throws IOException {
        ClassLoader loader = SableWireContract.class.getClassLoader();
        if (loader.getResource("dev/ryanhcode/sable/Sable.class") == null
                && loader.getResource(PREFIX + "ClientboundTeleportEntityPacketMixin.class") == null) return false;
        for (var entry : EXPECTED.entrySet()) {
            verify(loader, PREFIX + entry.getKey() + ".class", entry.getValue());
        }
        for (var entry : SPACE_EXPECTED.entrySet()) verify(loader, entry.getKey(), entry.getValue());
        return true;
    }

    private static void verify(ClassLoader loader, String resource, String expected) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) throw unsupported();
            byte[] bytes = in.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw unsupported();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!expected.equals(hash)) throw unsupported();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static IOException unsupported() {
        return new IOException("Sable 位置协议未核验，请使用普通回放模式；本补丁仅核验 Sable 2.0.5。");
    }

    private SableWireContract() {}
}
