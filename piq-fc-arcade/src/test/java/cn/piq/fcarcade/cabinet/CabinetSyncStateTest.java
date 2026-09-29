package cn.piq.fcarcade.cabinet;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetSyncStateTest {
    @Test void chunksAreOrderedAndWrongTokenDoesNotPoisonAssembly(){byte[] bytes={1,2,3,4};UUID token=UUID.randomUUID();var s=new CabinetSyncState(token,4,CabinetSyncState.hash(bytes));assertFalse(s.append(UUID.randomUUID(),0,new byte[]{1}));assertFalse(s.append(token,2,new byte[]{3,4}));assertTrue(s.append(token,0,new byte[]{1,2}));assertFalse(s.append(token,0,new byte[]{1,2}));assertTrue(s.append(token,2,new byte[]{3,4}));assertArrayEquals(bytes,s.finish());}
    @Test void corruptionCannotBecomeValidSnapshot(){var s=new CabinetSyncState(UUID.randomUUID(),1,CabinetSyncState.hash(new byte[]{1}));assertThrows(IllegalArgumentException.class,s::finish);}
    @Test void completedWrongHashIsRejected(){UUID t=UUID.randomUUID();var s=new CabinetSyncState(t,2,CabinetSyncState.hash(new byte[]{1,2}));assertTrue(s.append(t,0,new byte[]{2,1}));assertTrue(s.complete());assertThrows(IllegalArgumentException.class,s::finish);}
    @Test void copiedChunkDoesNotAliasCaller(){byte[] b={1,2};UUID t=UUID.randomUUID();var s=new CabinetSyncState(t,2,CabinetSyncState.hash(b));assertTrue(s.append(t,0,b));b[0]=9;assertArrayEquals(new byte[]{1,2},s.finish());}
    @Test void stateAndChunkLimitsAreHard(){assertThrows(IllegalArgumentException.class,()->new CabinetSyncState(UUID.randomUUID(),0,"0".repeat(64)));assertThrows(IllegalArgumentException.class,()->new CabinetSyncState(UUID.randomUUID(),CabinetSyncCore.MAX_STATE_BYTES+1,"0".repeat(64)));UUID t=UUID.randomUUID();var s=new CabinetSyncState(t,30000,"0".repeat(64));assertFalse(s.append(t,0,new byte[24577]));assertFalse(s.append(t,0,new byte[0]));assertThrows(IllegalStateException.class,s::bytesAfterAssembly);}
}
