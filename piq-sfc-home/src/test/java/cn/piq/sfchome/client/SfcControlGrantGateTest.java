package cn.piq.sfchome.client;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcControlGrantGateTest {
    @Test void hostCanRunBeforeAnyControllerAndRetiredLoanCannotReturn(){var gate=new SfcControlGrantGate();Object c=new Object();UUID a=UUID.randomUUID(),b=UUID.randomUUID();gate.bind(c,1,1);assertTrue(gate.mayGrant(c,1,1,a));assertTrue(gate.retire(c,1,1,a));assertFalse(gate.mayGrant(c,1,1,a));assertTrue(gate.mayGrant(c,1,1,b));}
    @Test void exactConnectionSessionAndEpochAllRequired(){var gate=new SfcControlGrantGate();Object c=new Object();UUID id=UUID.randomUUID();gate.bind(c,4,3);assertFalse(gate.mayGrant(new Object(),4,3,id));assertFalse(gate.mayGrant(c,5,3,id));assertFalse(gate.mayGrant(c,4,2,id));assertFalse(gate.retire(c,4,2,id));assertTrue(gate.mayGrant(c,4,3,id));}
    @Test void replayedBindDoesNotEraseTombstones(){var gate=new SfcControlGrantGate();Object c=new Object();UUID id=UUID.randomUUID();gate.bind(c,1,1);gate.retire(c,1,1,id);gate.bind(c,1,1);assertFalse(gate.mayGrant(c,1,1,id));}
    @Test void resetGetsIndependentGrantHistory(){var gate=new SfcControlGrantGate();Object c=new Object();UUID id=UUID.randomUUID();gate.bind(c,1,1);gate.retire(c,1,1,id);gate.bind(c,1,2);assertTrue(gate.mayGrant(c,1,2,id));assertFalse(gate.retire(c,1,1,id));}
    @Test void boundedHistoryNeverForgetsToAdmitOldLoans(){var gate=new SfcControlGrantGate();Object c=new Object();gate.bind(c,1,1);UUID first=UUID.randomUUID();gate.retire(c,1,1,first);for(int i=0;i<2000;i++)gate.retire(c,1,1,UUID.randomUUID());assertEquals(256,gate.retiredCount());assertFalse(gate.mayGrant(c,1,1,first));assertFalse(gate.mayGrant(c,1,1,UUID.randomUUID()));}
    @Test void disconnectClearsScopeWithoutAuthorizingAnything(){var gate=new SfcControlGrantGate();Object c=new Object();gate.bind(c,1,1);gate.clear();assertFalse(gate.current(c,1,1));assertFalse(gate.mayGrant(c,1,1,UUID.randomUUID()));}
}
