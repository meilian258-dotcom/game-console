package cn.piq.sfchome.client;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcWatchIdentityTest {
    private final SfcHomeNetwork.Session session=new SfcHomeNetwork.Session(1,1,ResourceLocation.parse("minecraft:overworld"),BlockPos.ZERO,UUID.randomUUID(),new BlockPos(2,0,0),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),SfcHomeNetwork.CORE_BUILD,0,UUID.randomUUID(),true,3,UUID.randomUUID(),UUID.randomUUID());
    private WatchDescriptor changed(UUID source,UUID lease){var d=SfcLocalWatchClient.descriptor(session);return new WatchDescriptor(d.provider(),source,lease,d.dimension(),d.origin(),d.link(),d.screens());}
    @Test void exactCurrentSourceReusesControllerRenderer(){assertTrue(SfcWatchClient.matches(SfcLocalWatchClient.descriptor(session),session));}
    @Test void sameHardwareWithNewHostOrSourceIsNotTheOldController(){
        assertFalse(SfcWatchClient.matches(changed(UUID.randomUUID(),session.mediaStream()),session));
        assertFalse(SfcWatchClient.matches(changed(session.mediaSource(),UUID.randomUUID()),session));
    }
    @Test void otherTelevisionOrProviderIsNotBlockedByLocalControl(){
        var d=SfcLocalWatchClient.descriptor(session);
        assertFalse(SfcWatchClient.matches(new WatchDescriptor(ResourceLocation.parse("example:md"),d.source(),d.hostLease(),d.dimension(),d.origin(),d.link(),d.screens()),session));
        assertFalse(SfcWatchClient.matches(new WatchDescriptor(d.provider(),d.source(),d.hostLease(),d.dimension(),d.origin(),d.link(),List.of(new WatchAnchor(new BlockPos(3,0,0),UUID.randomUUID()))),session));
        assertFalse(SfcWatchClient.matches(d,null));assertFalse(SfcWatchClient.matches(null,session));
    }
}
