package cn.piq.fcarcade.home;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Runs actual production geometry, ownership ledger and callback removal gate without Minecraft. */
class PanelTvLifecycleTest {
    @Test void fourAndSixCellsExactlyCoverBothMountingsInEveryFacing() {
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true})for(var f:PanelTvFootprint.Facing.values()) {
            int count=wide?6:4;
            var cells=PanelTvFootprint.cells(f,wide);
            assertEquals(count,cells.size());assertEquals(count,cells.stream().distinct().count());
            var style=wide?(wall?ArcadeDisplayStyle.HOME_PANEL_3_WALL:ArcadeDisplayStyle.HOME_PANEL_3)
                    :(wall?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_2);
            var whole=UserTvLayout.bounds(style,f.ordinal());double volume=0;
            for(var c:cells) {
                assertEquals(c.part()/(wide?3:2),c.y());
                var selection=PanelTvFootprint.selection(f,c.part(),wide,wall);
                assertEquals(whole.minX(),selection.minX()+c.x()*16,1e-9);
                assertEquals(whole.maxX(),selection.maxX()+c.x()*16,1e-9);
                assertEquals(whole.minY(),selection.minY()+c.y()*16,1e-9);
                assertEquals(whole.maxY(),selection.maxY()+c.y()*16,1e-9);
                assertEquals(whole.minZ(),selection.minZ()+c.z()*16,1e-9);
                assertEquals(whole.maxZ(),selection.maxZ()+c.z()*16,1e-9);
                var clipped=PanelTvFootprint.clipped(f,c.part(),wide,wall);
                assertTrue(clipped.minX()>=0&&clipped.maxX()<=16&&clipped.minY()>=0&&clipped.maxY()<=16&&clipped.minZ()>=0&&clipped.maxZ()<=16);
                assertTrue(clipped.maxX()>clipped.minX()&&clipped.maxY()>clipped.minY()&&clipped.maxZ()>clipped.minZ());
                volume+=(clipped.maxX()-clipped.minX())*(clipped.maxY()-clipped.minY())*(clipped.maxZ()-clipped.minZ());
            }
            assertEquals((whole.maxX()-whole.minX())*(whole.maxY()-whole.minY())*(whole.maxZ()-whole.minZ()),volume,1e-7);
            assertTrue(PanelTvFootprint.canPlace(f,wide,c->true));
            for(int denied=0;denied<count;denied++){final int part=denied;assertFalse(PanelTvFootprint.canPlace(f,wide,c->c.part()!=part));}
            assertThrows(IllegalArgumentException.class,()->PanelTvFootprint.cell(f,-1,wide));
            assertThrows(IllegalArgumentException.class,()->PanelTvFootprint.cell(f,count,wide));
        }
    }

    @Test void everyCellClaimsOnlyOneItemAndReloadRetainsWidthAndMounting() {
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true})for(var f:PanelTvFootprint.Facing.values())for(var clicked:PanelTvFootprint.cells(f,wide)) {
            var ledger=new PanelTvAssemblyLedger();var id=UUID.randomUUID();
            assertTrue(ledger.restore(new PanelTvAssemblyLedger.Assembly(id,15,63,15,f,false,0,wide,wall)));
            assertTrue(ledger.close(id,clicked.part(),15+clicked.x(),63+clicked.y(),15+clicked.z()));
            ledger.acknowledge(id,clicked.part());
            var loaded=new PanelTvAssemblyLedger();assertTrue(loaded.restore(ledger.get(id)));
            assertEquals(wide,loaded.get(id).wide());assertEquals(wall,loaded.get(id).wall());
            for(var c:PanelTvFootprint.cells(f,wide)) {
                assertFalse(loaded.close(id,c.part(),15+c.x(),63+c.y(),15+c.z()));
                loaded.acknowledge(id,c.part());
            }
            assertNull(loaded.get(id));
        }
    }

    @Test void cancelledPlacementCannotDropAndWrongCoordinatesCannotCancelIt() {
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true}) {
            var ledger=new PanelTvAssemblyLedger();var id=UUID.randomUUID();
            ledger.restore(new PanelTvAssemblyLedger.Assembly(id,0,0,0,PanelTvFootprint.Facing.NORTH,false,0,wide,wall));
            ledger.awaitPlacementEvent(id);assertTrue(ledger.pending(id));
            assertFalse(ledger.close(id,0,0,0,0));assertFalse(ledger.cancelPlacement(id,0,0,0,1));
            assertTrue(ledger.cancelPlacement(id,0,0,0,0));assertNull(ledger.get(id));
            assertFalse(ledger.close(id,0,0,0,0));assertFalse(ledger.pending(id));
        }
    }

    @Test void interruptedPlacementKeepsNonRefundableTombstoneUntilAllChunksReturn() {
        for(boolean wide:new boolean[]{false,true})for(boolean wall:new boolean[]{false,true}) {
            var ledger=new PanelTvAssemblyLedger();var id=UUID.randomUUID();
            ledger.restore(new PanelTvAssemblyLedger.Assembly(id,15,0,15,PanelTvFootprint.Facing.NORTH,false,0,wide,wall));
            ledger.awaitPlacementEvent(id);ledger.abortPlacement(id);
            assertFalse(ledger.pending(id));assertTrue(ledger.get(id).closed());
            ledger.acknowledge(id,0);assertNotNull(ledger.get(id));
            var loaded=new PanelTvAssemblyLedger();assertTrue(loaded.restore(ledger.get(id)));
            assertEquals(wide,loaded.get(id).wide());assertEquals(wall,loaded.get(id).wall());
            for(var c:PanelTvFootprint.cells(PanelTvFootprint.Facing.NORTH,wide)) {
                assertFalse(loaded.close(id,c.part(),15+c.x(),c.y(),15+c.z()));loaded.acknowledge(id,c.part());
            }
            assertNull(loaded.get(id));
        }
    }

    @Test void confirmedPlacementIsNotLaterCancelledAndMalformedOwnershipIsRejected() {
        for(boolean wide:new boolean[]{false,true}) {
            var ledger=new PanelTvAssemblyLedger();var id=UUID.randomUUID();
            var assembly=new PanelTvAssemblyLedger.Assembly(id,0,0,0,PanelTvFootprint.Facing.EAST,false,0,wide,true);
            assertTrue(ledger.restore(assembly));assertFalse(ledger.restore(assembly));
            ledger.awaitPlacementEvent(id);ledger.confirmPlacement(id);assertFalse(ledger.cancelPlacement(id,0,0,0,0));
            assertFalse(ledger.close(id,PanelTvFootprint.cellCount(wide),0,0,0));assertFalse(ledger.close(id,0,1,0,0));
            assertTrue(ledger.close(id,0,0,0,0));
            assertFalse(ledger.restore(new PanelTvAssemblyLedger.Assembly(UUID.randomUUID(),0,0,0,PanelTvFootprint.Facing.NORTH,false,1,wide,true)));
            assertFalse(ledger.restore(new PanelTvAssemblyLedger.Assembly(UUID.randomUUID(),0,0,0,PanelTvFootprint.Facing.NORTH,true,1<<PanelTvFootprint.cellCount(wide),wide,true)));
        }
    }

    @Test void unloadedOrDeniedCellsStopBeforeCallbacksAndCommit() {
        for(boolean wide:new boolean[]{false,true})for(var f:PanelTvFootprint.Facing.values())for(var blocked:PanelTvFootprint.cells(f,wide)) {
            var events=new AtomicInteger();var commits=new AtomicInteger();
            var result=PanelTvRemovalGate.attempt(f,0,wide,c->c.part()!=blocked.part(),c->true,c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet);
            assertEquals(PanelTvRemovalGate.Result.UNLOADED,result);assertEquals(0,events.get());assertEquals(0,commits.get());
            result=PanelTvRemovalGate.attempt(f,0,wide,c->true,c->c.part()!=blocked.part(),c->true,c->{events.incrementAndGet();return true;},()->true,commits::incrementAndGet);
            assertEquals(PanelTvRemovalGate.Result.DENIED,result);assertEquals(0,events.get());assertEquals(0,commits.get());
        }
    }

    @Test void callbacksCannotRevokePermissionUnloadOrReplaceAnyCellThenCommit() {
        for(boolean wide:new boolean[]{false,true})for(int change=0;change<4;change++) {
            final int mode=change;var changed=new AtomicBoolean();var commits=new AtomicInteger();
            var result=PanelTvRemovalGate.attempt(PanelTvFootprint.Facing.NORTH,0,wide,
                    c->mode!=0||!changed.get()||c.part()!=0,
                    c->mode!=1||!changed.get()||c.part()!=0,
                    c->mode!=2||!changed.get()||c.part()!=0,
                    c->{changed.set(true);return true;},()->mode!=3||!changed.get(),commits::incrementAndGet);
            assertEquals(change==0?PanelTvRemovalGate.Result.UNLOADED:change==1?PanelTvRemovalGate.Result.DENIED:PanelTvRemovalGate.Result.REPLACED,result);
            assertEquals(0,commits.get());
        }
    }

    @Test void foreignReplacementIsNeverSentAnExtraBreakEvent() {
        for(boolean wide:new boolean[]{false,true}) {
            var events=new ArrayList<Integer>();var commits=new AtomicInteger();
            var result=PanelTvRemovalGate.attempt(PanelTvFootprint.Facing.WEST,0,wide,c->true,c->true,c->c.part()!=2,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet);
            assertEquals(PanelTvRemovalGate.Result.REMOVED,result);assertFalse(events.contains(2));assertFalse(events.contains(0));
            assertEquals(PanelTvFootprint.cellCount(wide)-2,events.size());assertEquals(1,commits.get());
        }
    }

    @Test void successfulBreakFiresEveryOtherOwnedCellExactlyOnce() {
        for(boolean wide:new boolean[]{false,true})for(var f:PanelTvFootprint.Facing.values())for(var clicked:PanelTvFootprint.cells(f,wide)) {
            var events=new ArrayList<Integer>();var commits=new AtomicInteger();
            var result=PanelTvRemovalGate.attempt(f,clicked.part(),wide,c->true,c->true,c->true,c->{events.add(c.part());return true;},()->true,commits::incrementAndGet);
            assertEquals(PanelTvRemovalGate.Result.REMOVED,result);assertEquals(PanelTvFootprint.cellCount(wide)-1,events.size());
            assertEquals(events.size(),events.stream().distinct().count());assertFalse(events.contains(clicked.part()));assertEquals(1,commits.get());
        }
    }

    @Test void deniedExtraBreakEventAndInvalidClickedPartNeverCommit() {
        for(boolean wide:new boolean[]{false,true})for(int denied=1;denied<PanelTvFootprint.cellCount(wide);denied++) {
            int part=denied;var commits=new AtomicInteger();
            assertEquals(PanelTvRemovalGate.Result.DENIED,PanelTvRemovalGate.attempt(PanelTvFootprint.Facing.NORTH,0,wide,c->true,c->true,c->true,c->c.part()!=part,()->true,commits::incrementAndGet));
            assertEquals(PanelTvRemovalGate.Result.REPLACED,PanelTvRemovalGate.attempt(PanelTvFootprint.Facing.NORTH,PanelTvFootprint.cellCount(wide),wide,c->true,c->true,c->true,c->true,()->true,commits::incrementAndGet));
            assertEquals(0,commits.get());
        }
    }

    public static void main(String[] args)throws Exception {
        var instance=new PanelTvLifecycleTest();int count=0;
        for(var m:PanelTvLifecycleTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(instance);count++;}
        System.out.println("Panel production pure lifecycle tests passed: "+count+"; no ROM/game/Gradle test locks used");
    }
}
