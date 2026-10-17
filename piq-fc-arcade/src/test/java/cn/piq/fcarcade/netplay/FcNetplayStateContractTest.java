package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.ArcadeSessionPayload;
import cn.piq.fcarcade.session.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcNetplayStateContractTest {
    @Test void explicitOfficialRouteRoundTripsAndCannotReplaceAnExistingRoom() {
        var old=state(false,false);
        var selected=new NetplayNetwork.State(old.session(),old.ticket(),old.player(),false,
                FcNetplayBackend.launch(false,false,FcNetplayBackend.OFFICIAL_PAD));
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            NetplayNetwork.State.CODEC.encode(buf,selected);
            var decoded=NetplayNetwork.State.CODEC.decode(buf);
            assertEquals(selected,decoded);assertEquals(0,buf.readableBytes());
            assertDoesNotThrow(()->decoded.verifyContinuation(null));
            assertDoesNotThrow(()->decoded.verifyContinuation(selected));
            assertThrows(IllegalArgumentException.class,()->decoded.verifyContinuation(old));
            assertThrows(IllegalArgumentException.class,()->old.verifyContinuation(decoded));
        }finally{buf.release();}
    }

    private static ArcadeSessionPayload session(boolean gun, String rom, long revision, boolean host, ArcadeRole role) {
        return new ArcadeSessionPayload(BlockPos.ZERO, 7, ArcadeMode.LOCKSTEP, role, 1, "", 16, 16, 100,
                rom, 1, false, true, gun ? NesCoreVariant.LIBRETRO_ZAPPER_V1 : NesCoreVariant.LIBRETRO_V1,
                true, host, role.controllerIndex() < 0 ? null : UUID.randomUUID(), revision, false);
    }
    private static NetplayNetwork.State state(boolean gun, boolean jni) {
        return new NetplayNetwork.State(session(gun, "a".repeat(64), 1, true, ArcadeRole.SPECTATOR), UUID.randomUUID(), true, jni);
    }
    @Test void everyFcBackendRoundTripsItsExplicitLaunchAndRejectsTruncation() {
        for (boolean gun : new boolean[]{false,true}) for (boolean jni : new boolean[]{false,true}) {
            var original = state(gun,jni);
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                NetplayNetwork.State.CODEC.encode(buf, original);
                byte[] bytes = new byte[buf.readableBytes()]; buf.getBytes(0,bytes);
                var decoded = NetplayNetwork.State.CODEC.decode(buf);
                assertEquals(original,decoded); assertEquals(0,buf.readableBytes());
                assertEquals(FcNetplayBackend.launch(gun,jni),decoded.launch());
                assertDoesNotThrow(() -> decoded.verifyContinuation(original));
                for (int n=0;n<bytes.length;n++) {
                    var shortBuf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(bytes,n)), RegistryAccess.EMPTY);
                    try { assertThrows(RuntimeException.class, () -> NetplayNetwork.State.CODEC.decode(shortBuf)); }
                    finally { shortBuf.release(); }
                }
            } finally { buf.release(); }
        }
    }
    @Test void oldJavaConstructorsResolveLocallyButLegacyOrForeignWireIdentityIsRejected() {
        var original=state(false,false);
        assertEquals(original.launch(),new NetplayNetwork.State(original.session(),original.ticket(),true).launch());
        for(var grant:List.of(NetplayLaunchSpec.UNSPECIFIED,FcNetplayBackend.launch(false,true),
                new NetplayLaunchSpec(original.launch().variant(),"f".repeat(64),true),
                new NetplayLaunchSpec(original.launch().variant(),original.launch().compatibilitySha(),false))) {
            var bad=new NetplayNetwork.State(original.session(),original.ticket(),true,false,grant);
            assertThrows(IllegalArgumentException.class,()->bad.verifyContinuation(null));
        }
    }
    @Test void oneSessionCannotChangeBackendGunModeOrContent() {
        for(boolean jni:new boolean[]{false,true}) {
            var old=state(false,jni);
            assertThrows(IllegalArgumentException.class,()->state(false,!jni).verifyContinuation(old));
            assertThrows(IllegalArgumentException.class,()->state(true,jni).verifyContinuation(old));
            var differentRom=new NetplayNetwork.State(session(false,"b".repeat(64),2,true,ArcadeRole.SPECTATOR),UUID.randomUUID(),true,jni);
            assertThrows(IllegalArgumentException.class,()->differentRom.verifyContinuation(old));
        }
    }
    @Test void freshSeatTicketDoesNotChangeFrozenBackend() {
        for(boolean jni:new boolean[]{false,true}) {
            var old=new NetplayNetwork.State(session(false,"a".repeat(64),1,false,ArcadeRole.SPECTATOR),UUID.randomUUID(),false,jni);
            var joined=new NetplayNetwork.State(session(false,"a".repeat(64),2,false,ArcadeRole.PLAYER_TWO),UUID.randomUUID(),true,jni);
            assertDoesNotThrow(()->joined.verifyContinuation(old));
            assertNotEquals(old.ticket(),joined.ticket());
        }
    }
}
