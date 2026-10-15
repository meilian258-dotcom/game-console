// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcControlExitDiagnosticsTest {
    @Test void expiredPortRecordsExactWatchdogAgeWithoutExtendingItsDeadline(){
        var health=new SfcInputHealth();health.start(100);health.packet(1,104);
        var before=health.sample(1,204);assertEquals(100,before.ageTicks());assertFalse(health.expiredPort(1,204));
        var sample=health.sample(1,205);assertEquals(101,sample.ageTicks());assertEquals(104,sample.lastAcceptedTick());
        var evidence=SfcControlExitDiagnostics.capture("手柄连接超时",1,true,true,sample);
        assertEquals(SfcControlExitDiagnostics.Code.HEARTBEAT_TIMEOUT,evidence.code());
        assertTrue(evidence.text().contains("heartbeatAgeTicks=101"));assertTrue(evidence.text().contains("手柄连接超时"));
        assertTrue(health.expiredPort(1,205));assertEquals(sample,health.sample(1,205));
    }
    @Test void invalidBurstPreservesBudgetAndDistinctReason(){
        var health=new SfcInputHealth();health.start(0);
        for(int i=0;i<64;i++)assertTrue(health.packet(1,10));assertFalse(health.packet(1,10));
        var evidence=SfcControlExitDiagnostics.capture("Netplay 手柄心跳无效",1,true,true,health.sample(1,10));
        assertEquals(SfcControlExitDiagnostics.Code.HEARTBEAT_INVALID,evidence.code());
        assertEquals(65,evidence.heartbeat().packetsThisTick());assertEquals(0,evidence.heartbeat().ageTicks());
        assertFalse(health.packet(1,10));assertTrue(health.packet(1,11));
    }
    @Test void normalIdleHeartbeatsStayHealthyAndReturnIsNotTimeout(){
        var health=new SfcInputHealth();health.start(0);
        for(int tick=0;tick<1000;tick++){
            if(tick%2==0)assertTrue(health.packet(1,tick));
            assertFalse(health.expiredPort(1,tick));assertTrue(health.sample(1,tick).ageTicks()<2);
        }
        assertEquals(SfcControlExitDiagnostics.Code.RETURN_REQUEST,SfcControlExitDiagnostics.capture("手柄已归还，主机继续运行",1,true,true,health.sample(1,1000)).code());
    }
    @Test void borrowedButNotJoinedPortDoesNotInventHeartbeatAndUnknownTextStaysPrivate(){
        var health=new SfcInputHealth();health.start(0);
        var evidence=SfcControlExitDiagnostics.capture("private-player C:/private/game.sfc\nsecret",1,false,false,health.sample(1,999));
        assertNull(evidence.heartbeat());assertTrue(evidence.text().contains("heartbeat=not-active"));
        assertFalse(evidence.text().contains("private"));assertFalse(evidence.text().contains("secret"));
        assertEquals(SfcControlExitDiagnostics.Code.UNKNOWN_LOCAL_REASON,evidence.code());
        assertThrows(IllegalArgumentException.class,()->health.sample(-1,10));
        assertThrows(IllegalArgumentException.class,()->health.sample(2,10));
    }
}
