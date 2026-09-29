package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchRangeTest {
    private final UUID player=new UUID(0,1);
    private final Object connection=new Object();
    private final WatchLedger.Source source=new WatchLedger.Source(new UUID(0,2),new UUID(0,3));
    private WatchLedger.Candidate at(double distance,int entry){return new WatchLedger.Candidate(source,distance*distance,entry*entry,(entry+4)*(entry+4));}
    @Test void extendedAdmissionAndExitAreInclusive(){
        var ledger=new WatchLedger();
        assertNull(ledger.select(player,connection,List.of(at(32.01,32)),true,0));
        var lease=ledger.select(player,connection,List.of(at(32,32)),true,1);
        assertNotNull(lease);
        assertSame(lease,ledger.select(player,connection,List.of(at(36,32)),true,2));
        assertNull(ledger.select(player,connection,List.of(at(36.01,32)),true,3));
    }
    @Test void shrinkingRangeRevokesExistingObserver(){
        var ledger=new WatchLedger();
        assertNotNull(ledger.select(player,connection,List.of(at(30,32)),true,0));
        assertNull(ledger.select(player,connection,List.of(at(30,8)),true,1));
        assertTrue(ledger.all().isEmpty());
    }
    @Test void rangeIsPerSourceAndNearestStillWins(){
        var ledger=new WatchLedger();
        var other=new WatchLedger.Source(new UUID(0,4),new UUID(0,5));
        var tooFar=new WatchLedger.Candidate(other,10*10,8*8,12*12);
        assertEquals(source,ledger.select(player,connection,List.of(tooFar,at(24,32)),true,0).source());
    }
    @Test void defaultConstructorRetainsExisting16And20(){
        var value=new WatchLedger.Candidate(source,0);
        assertEquals(256,value.enterSquared());assertEquals(400,value.exitSquared());
    }
    @Test void invalidAndUnboundedRangesRejected(){
        for(double[] bounds:new double[][]{{Double.NaN,400},{256,Double.POSITIVE_INFINITY},{0,400},{16385,17424},{256,255},{16384,17425}})
            assertThrows(IllegalArgumentException.class,()->new WatchLedger.Candidate(source,0,bounds[0],bounds[1]));
    }
    @Test void serviceAndFcSnapshotAdmissionShareActualDeviceRange()throws Exception{
        String watch=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/WatchService.java"));
        String fc=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/server/ServerArcadeSessions.java"));
        assertTrue(watch.contains("GameConsoleAdminSettings.watchRange"));
        assertTrue(watch.contains("(double)enter*enter,(double)exit*exit"));
        assertTrue(fc.contains("distance <= viewerDistanceSquared(server,candidate)"));
        assertTrue(fc.contains(": viewerDistanceSquared(player.getServer(),session)"));
        assertTrue(fc.contains("Math.max(old.viewDistance(),old.audioDistance())"));
        assertTrue(fc.contains("session.key.anchor(),globalSettings.viewDistance()"));
        assertTrue(fc.contains("role==ArcadeRole.SPECTATOR?cn.piq.fcarcade.config.GameConsoleAdminSettings.watchRange"));
        assertTrue(fc.contains("viewRange,config.audioDistance(),config.audioVolumePercent()"));
    }
    @Test void passiveWatchYieldsBeforePrivateAdmissionSourceGuard()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/PrivateHomeClient.java"));
        assertTrue(source.contains("registerBeforeStart(Runnable callback)"));
        assertTrue(source.contains("public static boolean isActiveOrClosing()"));
        assertTrue(source.contains("if(yieldObservers())Minecraft.getInstance().setScreen"));
        String start=source.substring(source.indexOf("static String start(Target target"));
        assertTrue(start.indexOf("if(!yieldObservers())")<start.indexOf("!valid(target)"));
    }
}
