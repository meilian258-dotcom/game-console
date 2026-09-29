package cn.piq.sfchome.server;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcInputHealthTest {
    @Test void startResetsLongPreparationTime(){var h=new SfcInputHealth();h.start(1000);assertFalse(h.expired(1000,true));}
    @Test void singlePlayerDoesNotRequireP2Heartbeat(){var h=new SfcInputHealth();h.start(0);assertTrue(h.packet(0,99));assertFalse(h.expired(101,false));assertTrue(h.expired(101,true));}
    @Test void heartbeatEveryTenTicksStaysHealthy(){var h=new SfcInputHealth();h.start(0);for(int t=0;t<10000;t+=10){assertTrue(h.packet(0,t));assertTrue(h.packet(1,t));assertFalse(h.expired(t,true));}}
    @Test void missingPacketEventuallyStops(){var h=new SfcInputHealth();h.start(0);assertFalse(h.expired(100,false));assertTrue(h.expired(101,false));}
    @Test void packetBudgetIsPerPortAndTick(){var h=new SfcInputHealth();h.start(0);for(int i=0;i<64;i++)assertTrue(h.packet(0,0));assertFalse(h.packet(0,0));assertTrue(h.packet(1,0));assertTrue(h.packet(0,1));}
    @Test void invalidPortOrBackwardsTickRejected(){var h=new SfcInputHealth();h.start(20);assertFalse(h.packet(-1,20));assertFalse(h.packet(2,20));assertFalse(h.packet(0,19));}
    @Test void stalledP2IsReportedSeparatelyWhileP1Continues(){var h=new SfcInputHealth();h.start(0);for(int t=0;t<=150;t+=10)assertTrue(h.packet(0,t));assertFalse(h.expiredPort(0,150));assertTrue(h.expiredPort(1,150));assertFalse(h.expired(150,false));}
    @Test void rejoinResetsSecondHealthAndBudget(){var h=new SfcInputHealth();h.start(0);for(int i=0;i<64;i++)assertTrue(h.packet(1,10));assertFalse(h.packet(1,10));assertTrue(h.expiredPort(1,111));h.start(200);assertFalse(h.expiredPort(0,200));assertFalse(h.expiredPort(1,200));assertTrue(h.packet(1,200));}
    @Test void perPortHealthRejectsInvalidIndexAndP2BudgetCannotChangeP1(){var h=new SfcInputHealth();h.start(10);assertThrows(IllegalArgumentException.class,()->h.expiredPort(-1,11));assertThrows(IllegalArgumentException.class,()->h.expiredPort(2,11));for(int i=0;i<64;i++)assertTrue(h.packet(1,11));assertFalse(h.packet(1,11));assertTrue(h.packet(0,11));assertFalse(h.expiredPort(0,111));assertTrue(h.expiredPort(0,112));}
    @Test void shortStallNeutralizesOnceWellBeforeLeaseExpiry(){
        var h=new SfcInputHealth();h.start(100);
        assertFalse(h.neutralizeStalePort(0,111));assertTrue(h.neutralizeStalePort(0,112));
        assertFalse(h.expiredPort(0,112));assertFalse(h.neutralizeStalePort(0,113));
        assertFalse(h.neutralizeStalePort(0,200));assertTrue(h.expiredPort(0,201));
    }
    @Test void heartbeatResetsOnlyItsOwnStaleDeadline(){
        var h=new SfcInputHealth();h.start(0);assertTrue(h.neutralizeStalePort(0,12));
        assertTrue(h.packet(0,13));assertFalse(h.neutralizeStalePort(0,24));
        assertTrue(h.neutralizeStalePort(0,25));assertTrue(h.neutralizeStalePort(1,25));
        h.start(30);assertFalse(h.neutralizeStalePort(0,41));assertTrue(h.neutralizeStalePort(0,42));
    }
    @Test void twoTickHeartbeatsAllowOrdinaryHeldButtonsWithoutFalseNeutralization(){
        var h=new SfcInputHealth();h.start(0);
        for(int t=0;t<1000;t++){if(t%2==0){assertTrue(h.packet(0,t));assertTrue(h.packet(1,t));}assertFalse(h.neutralizeStalePort(0,t));assertFalse(h.neutralizeStalePort(1,t));}
        assertThrows(IllegalArgumentException.class,()->h.neutralizeStalePort(-1,1000));
        assertThrows(IllegalArgumentException.class,()->h.neutralizeStalePort(2,1000));
    }
    @Test void newPortCannotPostponeAnotherPlayersStaleOrFinalExpiryDeadline(){
        var h=new SfcInputHealth();h.start(0);h.startPort(1,11);
        assertTrue(h.neutralizeStalePort(0,12));assertFalse(h.neutralizeStalePort(1,12));
        for(int t=20;t<=100;t+=10)h.startPort(1,t);
        assertTrue(h.expiredPort(0,101));assertFalse(h.expiredPort(1,101));
        assertFalse(h.neutralizeStalePort(0,101));
        assertThrows(IllegalArgumentException.class,()->h.startPort(-1,101));
        assertThrows(IllegalArgumentException.class,()->h.startPort(2,101));
    }
}
