package cn.piq.sfchome.server;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcFcInteractionSourceTest {
    String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+path+".java"));}
    @Test void airUseNeverReturnsAndBoundItemUseHasClickedAndBothEndpointChecks()throws Exception{
        String s=source("server/SfcHomeServer"),air=s.substring(s.indexOf("public static void useController("),s.indexOf("public static InteractionResult useControllerOn("));assertFalse(air.contains("release("));
        String bound=s.substring(s.indexOf("public static InteractionResult useControllerOn("),s.indexOf("private static String returnMessage"));
        for(String x:new String[]{"st.interactions.allow","HomeTvStructure.resolveAnchor","level.getBlockEntity(pos)!=clicked","p.getItemInHand(hand)!=item","authorizedStart(p,lease,connection)","HomeSystems.isCurrent(connection)","returnController(p.getServer(),lease)"})assertTrue(bound.contains(x),x);
    }
    @Test void cardAndCablePreflightPrecedeCreatingALease()throws Exception{
        String s=source("server/SfcHomeServer"),claim=s.substring(s.indexOf("private static void claim("),s.indexOf("public static void useController("));assertTrue(claim.indexOf("preflight(p,c,true)")<claim.indexOf("SfcControllerData.create("));assertTrue(claim.contains("reclaim(p,l)"));
        String block=source("world/SfcHomeConsoleBlock");assertTrue(block.contains("!cn.piq.sfchome.data.SfcCartridgeData.isCartridge(item)||player.isShiftKeyDown()"));
        assertFalse(s.contains("start(p,l,false)"));
    }
    @Test void SessionMembershipIsNotInputAuthorityAndTemporaryStorageClearsOnlyOnePort()throws Exception{
        String s=source("server/SfcHomeServer"),endpoint=s.substring(s.indexOf("private static boolean endpointFacts("),s.indexOf("private static int port("));assertTrue(endpoint.contains("validLease(p,l,false)"));
        String input=s.substring(s.indexOf("private static void acceptInput("),s.indexOf("public static void leave("));assertTrue(input.contains("endpointFacts(p,lease,s.connection)"));assertTrue(input.contains("SfcControllerAuthority.input(held(p,lease)&&!s.repairs.isolated(p.getUUID()),r.buttonMask(),r.forceRelease())"));assertTrue(input.contains("s.inputs[port].offer(r.sequence(),input.mask(),input.release())"));
        // Both execution lanes must clear only the now-unheld physical port, including its server core FIFO.
        assertTrue(s.contains("if(allowed&&!held(p,l)){s.inputs[l.port].clear();if(s.hosted!=null)s.hosted.releasePort(l.port);}"));
        assertTrue(input.contains("else if(input.release()&&s.hosted!=null)s.hosted.releasePort(port)"));
        assertTrue(s.contains("items.add(p.containerMenu.getCarried())"));assertTrue(s.contains("slot.container!=p.getInventory()"));
    }
    @Test void BothHandsAndOnlyConnectedTvDistanceAreSupported()throws Exception{
        String s=source("server/SfcHomeServer");assertTrue(s.contains("p.getMainHandItem()==l.stack||p.getOffhandItem()==l.stack"));assertTrue(s.contains("connection.isPresent()&&connection.get().console()==c"));assertTrue(s.contains("distance=Math.min(distance,p.distanceToSqr(connection.get().television().getBlockPos().getCenter()))"));
        String client=source("client/SfcHomeClient");assertTrue(client.contains("items.add(p.getMainHandItem());items.add(p.getOffhandItem())"));assertTrue(client.contains("localController(s,false)==null"));
    }
    @Test void openingGuiImmediatelyClearsInputAndRearmDoesNotConsumeKeyboardEdges()throws Exception{
        String s=source("client/SfcHomeClient");assertTrue(s.contains("screenOpening(ScreenEvent.Opening event)"));assertTrue(s.contains("INPUT_FOCUS.suspend();sendInput(true)"));assertTrue(s.contains("mask=INPUT_FOCUS.sample(mask)"));assertTrue(s.contains("@SubscribeEvent public static void key(InputEvent.Key event){if(playback!=null)sendInput(false);}"));
    }
    @Test void ControllerDoesNotTriggerVanillaUseAnimationOrBecomeTransferable()throws Exception{
        String s=source("item/SfcControllerItem");assertFalse(s.contains("startUsingItem"));assertFalse(s.contains("onDroppedByPlayer("));assertTrue(s.contains("SfcHomeServer.useControllerOn("));assertTrue(s.contains("SfcHomeServer.discardDroppedController(entity)"));
        String server=source("server/SfcHomeServer");assertTrue(server.contains("addListener(SfcHomeServer::tossedController)"));assertTrue(server.contains("event.getEntity().discard();event.setCanceled(true)"));
    }
    @Test void protectionCallbackCannotOverwriteAReentrantSessionOrChangedCard()throws Exception{
        String s=source("server/SfcHomeServer"),claim=s.substring(s.indexOf("private static void claim("),s.indexOf("private static Lease grant("));
        int check=claim.indexOf("preflight(p,c,true)");assertTrue(check>=0);String after=claim.substring(check,claim.indexOf("Lease lease=grant("));
        for(String token:new String[]{"st.sessions.get(c.hardwareId())!=s","s.join!=null","s.ports[requestedPort]!=null","candidate(p,s,false)"})assertTrue(after.contains(token),token);
        String start=s.substring(s.indexOf("private static boolean powerOn("),s.indexOf("private static void sendRuntime"));
        int preflight=start.indexOf("preflight(p,c,true)");assertTrue(preflight<start.indexOf("long id=++st.nextSession"));String guarded=start.substring(preflight,start.indexOf("long id=++st.nextSession"));
        // Pending final saves retain capacity; this is stronger than the old active-only count.
        for(String token:new String[]{"st.sessions.containsKey(c.hardwareId())","st.sessions.size()+st.stopping.size()>=4","st.leases.values().stream()","ItemStack.matches(card,c.insertedCartridge())","!rom.equals(c.romSha())","!checked.linkId().equals(connection.linkId())"})assertTrue(guarded.contains(token),token);
    }
}
