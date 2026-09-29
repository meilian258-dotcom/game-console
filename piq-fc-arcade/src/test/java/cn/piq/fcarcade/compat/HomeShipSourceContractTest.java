package cn.piq.fcarcade.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HomeShipSourceContractTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));
    }
    @Test void optionalApiIsIsolatedAndUnknownPlotsDoNotBecomeGround() throws Exception {
        var text=source("compat/HomeShipSpace");
        assertFalse(text.contains("import dev.ryanhcode"));
        assertTrue(text.contains("Class.forName(\"dev.ryanhcode.sable.Sable\", false, loader)"));
        assertTrue(text.contains("Class.forName(\"dev.ryanhcode.sable.ActiveSableCompanion\",false,loader).isInstance(api)"));
        assertTrue(text.contains("Boolean.TRUE.equals(inGrid.invoke(api, level, pos)) ? null : GROUND"));
        assertTrue(text.contains("++count > MAX_RAY_SHIPS"));
        assertTrue(text.contains("Math.min(from.x,to.x)-.001"));
        assertTrue(text.contains("Math.max(from.x,to.x)+.001"));
    }
    @Test void bothEndpointsBindShipIdentityAndKeepLedgerPaymentAndProtection() throws Exception {
        var endpoint=source("home/HomeEndpointBlockEntity");
        assertTrue(endpoint.contains("tag.putUUID(\"LinkShipId\",linkShipId)"));
        assertTrue(endpoint.contains("HomeShipSpace.bindingMatches("));
        var hardware=source("home/HomeHardware");
        assertTrue(hardware.contains("!console.linkSpaceValid() || !tv.linkSpaceValid()"));
        assertTrue(hardware.contains("!endpoint.linkSpaceValid() || !actual.linkSpaceValid()"));
        assertTrue(hardware.contains("HomeLinkLedger.inRange(console.endpoint(), tv.endpoint())"));
        assertTrue(hardware.contains("cablePermission(player, pos, hand, hit) && facts.getAsBoolean()"));
        assertTrue(hardware.contains("data.ledger.connect(console.endpoint(), tv.endpoint())"));
        assertTrue(hardware.contains("wire.shrink(1)"));
        assertTrue(hardware.contains("SuborConsoleBlock"));
    }
    @Test void shipRaysCheckAllLoadedSpacesWithoutReplacingPlayerAuthority() throws Exception {
        var ray=source("home/HomeShipRay");
        assertTrue(ray.contains("HomeShipSpace.rayFrames(level,worldFrom,worldTo)"));
        assertTrue(ray.contains("!level.hasChunkAt(pos)"));
        assertTrue(ray.contains("shape.clip(from,to,pos)"));
        assertFalse(ray.contains("getChunk("));
        var controls=source("home/HomeApplianceService");
        assertTrue(controls.contains("HomeShipRay.trace(level,player,eye,end,frame,true"));
        assertTrue(controls.contains("HomeHardware.mayUse(player,clicked)"));
        assertTrue(controls.contains("event.getUseBlock() == TriState.FALSE"));
        assertTrue(controls.contains("new LoadedView(level).clip")); // Original ground path remains.
        var gun=source("home/HomeZapperAim");
        assertTrue(gun.contains("!HomeHardware.connected(level,console,tv)"));
        assertTrue(gun.contains("entitiesClear(player,worldEye,worldEnd"));
        assertTrue(gun.contains("java.util.Objects.equals(ship,frame.id())"));
    }
    @Test void handCordConvertsWorldGripButNeverReappliesBerShipPose() throws Exception {
        var text=source("client/ControllerCableRenderer");
        assertTrue(text.contains("style == Style.FAMICOM"));
        assertTrue(text.contains("HomeShipSpace.localPoint(console.getLevel(),console.getBlockPos(),grip,partial)"));
        assertTrue(text.contains("Vec3 end = grip.subtract(origin)"));
        assertFalse(text.contains("poses.mulPose"));
        assertFalse(text.contains("poses.translate"));
    }
}
