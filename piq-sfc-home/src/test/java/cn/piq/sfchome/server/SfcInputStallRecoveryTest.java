package cn.piq.sfchome.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcInputStallRecoveryTest {
    @Test void stalledPortStopsWithinTwelveTicksWhileOtherPortContinuesAndLeaseRemains(){
        var health=new SfcInputHealth();health.start(0);
        var ports=new SfcInputTimeline[]{new SfcInputTimeline(),new SfcInputTimeline()};
        assertTrue(ports[0].offer(0,1,false));assertTrue(ports[1].offer(0,512,false));
        for(int tick=0;tick<=40;tick++){
            if(tick%2==0)assertTrue(health.packet(1,tick));
            for(int port=0;port<2;port++)if(health.neutralizeStalePort(port,tick))ports[port].neutralizeStale();
            assertEquals(tick<12?1:0,ports[0].next());assertEquals(512,ports[1].next());
            assertFalse(health.expiredPort(0,tick));assertFalse(health.expiredPort(1,tick));
        }
        // Delayed non-zero heartbeats carry increasing sequences, but cannot revive the stale key.
        assertTrue(health.packet(0,41));assertTrue(ports[0].offer(1,1,false));assertEquals(0,ports[0].next());
        assertTrue(ports[0].offer(2,0,true));assertTrue(ports[0].offer(3,8,false));assertEquals(8,ports[0].next());
    }

    @Test void serverNeutralizesOnlyOccupiedPortBeforeConsumingNextLocalOrHostedInput()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/server/SfcHomeServer.java"));
        String tick=source.substring(source.indexOf("private static void tick("),source.indexOf("private static void stop("));
        int clear=tick.indexOf("s.health.neutralizeStalePort(l.port,st.tick)");
        assertTrue(clear>tick.indexOf("if(s.clock==null)"));
        assertTrue(tick.contains("for(Lease l:s.ports)if(l!=null&&s.health.neutralizeStalePort"));
        assertTrue(tick.contains("s.inputs[l.port].neutralizeStale()"));
        assertTrue(tick.contains("if(s.hosted!=null)s.hosted.releasePort(l.port)"));
        assertTrue(clear<tick.indexOf("s.hosted.inputs(s.inputs[0].next(),s.inputs[1].next())"));
        assertTrue(clear<tick.indexOf("int n=s.clock.tick()"));
        assertTrue(source.contains("s.inputs[requestedPort]=new SfcInputTimeline();s.health.startPort(requestedPort,st.tick)"));
        assertTrue(source.contains("s.inputs[lease.port]=new SfcInputTimeline();s.health.startPort(lease.port,st.tick)"));
        assertTrue(source.contains("s.inputs[j.port]=new SfcInputTimeline();s.health.startPort(j.port,st.tick)"));
    }
}
