package cn.piq.sfchome.server;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executable range/revocation rules plus wiring checks; not a real-player server test. */
class SfcControllerCableTest {
    static final class Item {
        final UUID id;final boolean controller;int count=1,removals;
        Item(UUID id,boolean controller){this.id=id;this.controller=controller;}
        UUID identity(){return controller&&count>0?id:null;}
        void remove(){count=0;removals++;}
    }
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+name+".java"));}
    String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b,s.indexOf(a)));}
    @Test void sixBlocksIsInclusiveAndTheNextRepresentableDistanceIsOutside(){
        assertEquals(6,SfcControllerAuthority.MAX_CABLE_DISTANCE);
        assertTrue(SfcControllerAuthority.withinCableDistance(0));
        assertTrue(SfcControllerAuthority.withinCableDistance(Math.nextDown(36.0)));
        assertTrue(SfcControllerAuthority.withinCableDistance(36));
        assertFalse(SfcControllerAuthority.withinCableDistance(Math.nextUp(36.0)));
        assertFalse(SfcControllerAuthority.withinCableDistance(36.000001));
    }
    @Test void fullThreeDimensionalDistanceAndMalformedDistancesFailClosed(){
        assertTrue(SfcControllerAuthority.withinCableDistance(2*2+4*4+4*4));
        assertFalse(SfcControllerAuthority.withinCableDistance(2*2+4*4+4.001*4.001));
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MAX_VALUE})assertFalse(SfcControllerAuthority.withinCableDistance(bad));
    }
    @Test void movingAwayRevokesOnlyThatPortsQueueAndPhysicalCopies(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();var a=new Item(first,true);var b=new Item(second,true);
        var p1=new SfcInputTimeline();var p2=new SfcInputTimeline();assertTrue(p1.offer(1,1,false));assertTrue(p2.offer(1,512,false));
        if(!SfcControllerAuthority.withinCableDistance(36.01)){p1.clear();assertEquals(1,SfcControllerInventory.revoke(first,List.of(a,b),Item::identity,Item::remove));}
        assertEquals(0,p1.next());assertEquals(512,p2.next());assertEquals(0,a.count);assertEquals(1,b.count);
        assertTrue(p2.offer(2,0,false));assertEquals(0,p2.next());
    }
    @Test void cursorReplacementAndDistinctDuplicatesAreRemovedRatherThanOnlyTheOldReference(){
        UUID id=UUID.randomUUID();var original=new Item(id,true);var cursor=new Item(id,true);var duplicate=new Item(id,true);
        assertEquals(3,SfcControllerInventory.revoke(id,List.of(original,cursor,cursor,duplicate),Item::identity,Item::remove));
        for(Item item:List.of(original,cursor,duplicate)){assertEquals(0,item.count);assertEquals(1,item.removals);}
        assertEquals(0,SfcControllerInventory.revoke(id,List.of(original,cursor,duplicate),Item::identity,Item::remove));
    }
    @Test void sameNbtOnOtherItemTypeAndDifferentTokensAreNeverRemoved(){
        UUID id=UUID.randomUUID();var impostor=new Item(id,false);var other=new Item(UUID.randomUUID(),true);var own=new Item(id,true);
        assertEquals(1,SfcControllerInventory.revoke(id,Arrays.asList(null,impostor,other,own,own),Item::identity,Item::remove));
        assertEquals(1,impostor.count);assertEquals(1,other.count);assertEquals(0,own.count);
        assertEquals(0,SfcControllerInventory.revoke(null,List.of(other),Item::identity,Item::remove));
    }
    @Test void leaseAndJoinRechecksUseOnlyConsoleRangeButHostNeverDoes()throws Exception{
        String server=source("server/SfcHomeServer");
        String range=between(server,"private static boolean cableReach(","private static void removeControllerCopies(");
        assertTrue(range.contains("p.serverLevel()==c.getLevel()"));assertTrue(range.contains("p.distanceToSqr(c.getBlockPos().getCenter())"));
        assertFalse(range.contains("television"));assertFalse(range.contains("Math.min"));
        assertTrue(between(server,"private static boolean validLease(","private static void reclaim(").contains("!cableReach(p,l.console)"));
        String candidate=between(server,"private static boolean candidate(","private static boolean joinValid(");
        assertTrue(candidate.contains("!cableReach(p,console)"));assertTrue(candidate.contains("return basic(p,console)&&cableReach(p,console)"));
        assertTrue(between(server,"private static Lease grant(","public static void useController(").contains("!cableReach(p,c)"));
        String host=between(server,"private static boolean hostValid(","private static boolean host(");assertFalse(host.contains("cableReach"));assertFalse(host.contains("distanceToSqr"));
        String client=between(source("client/SfcHomeClient"),"private static boolean presentController(","static boolean acceptsInput(");
        assertTrue(client.contains("withinCableDistance(mc.player.distanceToSqr(playback.session.consolePos().getCenter()))"));
    }
    @Test void everyLeaseExpiryActuallyCleansCurrentInventoryMenusAndCursor()throws Exception{
        String server=source("server/SfcHomeServer");
        String remove=between(server,"private static void removeControllerCopies(","private static boolean validLease(");
        for(String token:new String[]{"personalItems(p)","p.inventoryMenu.getCarried()","p.containerMenu.slots","SfcControllerData.isController(item)?SfcControllerData.leaseId(item):null","SfcControllerInventory.revoke(","slot.setChanged()","p.containerMenu.broadcastChanges()"})assertTrue(remove.contains(token),token);
        String release=between(server,"private static void release(MinecraftServer","private static void releaseConsole(");
        assertTrue(release.contains("st.leases.remove(l.id)"));assertTrue(release.contains("removeControllerCopies(p,l.id)"));assertTrue(release.contains("l.stack.setCount(0)"));
        assertFalse(release.contains("stop("));assertFalse(release.contains("s.inputs[0]"));assertFalse(release.contains("s.inputs[1]"));
    }
    @Test void visualReceiptsAreSetAtGrantClearedAtReleaseAndNeverUsedAsAuthority()throws Exception{
        String server=source("server/SfcHomeServer"),be=source("world/SfcHomeConsoleBlockEntity");
        assertTrue(server.contains("c.setControllerVisual(port,p.getUUID(),id)"));assertTrue(server.contains("l.console.setControllerLeased(l.port,false)"));
        assertFalse(server.contains("controllerVisualPlayer("));assertFalse(server.contains("controllerVisualLease("));
        for(String name:new String[]{"controllerVisualPlayer(int port)","controllerVisualLease(int port)","t.hasUUID(\"SfcControllerPlayer\"+port)","t.hasUUID(\"SfcControllerLease\"+port)","setControllerVisual(port,null,null)","java.util.Arrays.fill(controllerPlayers,null)","java.util.Arrays.fill(controllerLeases,null)"})assertTrue(be.contains(name),name);
        assertTrue(be.contains("level.isClientSide||!level.getServer().isSameThread()"));
    }
}
