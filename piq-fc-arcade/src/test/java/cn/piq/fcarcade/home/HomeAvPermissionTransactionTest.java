package cn.piq.fcarcade.home;

import cn.piq.fcarcade.server.InteractionTransaction;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual link/refund ledger behind the same permission transaction, without a Minecraft world. */
class HomeAvPermissionTransactionTest {
    private final HomeLinkLedger ledger=new HomeLinkLedger();
    private final InteractionTransaction gate=new InteractionTransaction();
    private final HomeLinkLedger.Endpoint console=new HomeLinkLedger.Endpoint(new UUID(0,1),"minecraft:overworld",0,64,0,HomeLinkLedger.Kind.CONSOLE);
    private final HomeLinkLedger.Endpoint tv=new HomeLinkLedger.Endpoint(new UUID(0,2),"minecraft:overworld",4,64,0,HomeLinkLedger.Kind.TV);
    @Test void deniedFirstEndpointCannotCreateLinkOrConsumeCable() {
        var cable=new AtomicInteger(1);
        assertFalse(gate.run(()->true,()->false,()->{assertNotNull(ledger.connect(console,tv));cable.decrementAndGet();}));
        assertTrue(ledger.snapshots().isEmpty());assertEquals(1,cable.get());
    }
    @Test void bothPermissionsStillAllowOriginalLinkAndSingleRefund() {
        var cable=new AtomicInteger(1);var installed=new AtomicReference<HomeLinkLedger.Link>();
        assertTrue(gate.run(()->true,()->true,()->{installed.set(ledger.connect(console,tv));cable.decrementAndGet();}));
        assertNotNull(installed.get());assertEquals(0,cable.get());
        assertTrue(ledger.close(installed.get().id(),console).refundCable());
        assertFalse(ledger.close(installed.get().id(),tv).refundCable());
    }
    @Test void callbackReplacingFirstEndpointRejectsCapturedTransaction() {
        var first=new AtomicReference<>(console);var cable=new AtomicInteger(1);
        assertFalse(gate.run(()->first.get()==console,()->{first.set(new HomeLinkLedger.Endpoint(new UUID(0,3),console.dimension(),0,64,0,console.kind()));return true;},()->{ledger.connect(console,tv);cable.decrementAndGet();}));
        assertTrue(ledger.snapshots().isEmpty());assertEquals(1,cable.get());
    }
    @Test void deniedPeerCannotDisconnectExistingLinkOrRefund() {
        var link=ledger.connect(console,tv);var refunded=new AtomicInteger();
        assertFalse(gate.run(()->ledger.get(link.id())==link,()->false,()->{if(ledger.close(link.id(),tv).refundCable())refunded.incrementAndGet();}));
        assertEquals(link,ledger.get(link.id()));assertEquals(0,refunded.get());
    }
    @Test void permissionReentryCannotInstallSecondLink() {
        var commits=new AtomicInteger();
        assertTrue(gate.run(()->ledger.activeFor(console)==null,()->{
            assertFalse(gate.run(()->true,()->true,()->{ledger.connect(console,tv);commits.incrementAndGet();}));return true;
        },()->{assertNotNull(ledger.connect(console,tv));commits.incrementAndGet();}));
        assertEquals(1,commits.get());assertEquals(1,ledger.snapshots().size());
    }
}
