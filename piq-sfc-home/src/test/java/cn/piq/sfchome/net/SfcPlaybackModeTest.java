package cn.piq.sfchome.net;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcPlaybackModeTest {
    @Test void playerMediaRunsOnlyItsExecutionHost(){
        assertFalse(SfcPlaybackMode.receivesMedia(0,true));
        assertTrue(SfcPlaybackMode.receivesMedia(0,false));
        assertTrue(SfcPlaybackMode.permitsRom(0,true));
        assertFalse(SfcPlaybackMode.permitsRom(0,false));
        assertFalse(SfcPlaybackMode.checksState(0));
    }
    @Test void localSyncKeepsBothCoresAndConsistencyChecks(){
        for(boolean host:new boolean[]{false,true}){
            assertFalse(SfcPlaybackMode.receivesMedia(1,host));
            assertTrue(SfcPlaybackMode.permitsRom(1,host));
        }
        assertTrue(SfcPlaybackMode.checksState(1));
    }
    @Test void serverHostedNeverGrantsClientCoreOrRom(){
        for(boolean host:new boolean[]{false,true}){
            assertTrue(SfcPlaybackMode.receivesMedia(2,host));
            assertFalse(SfcPlaybackMode.permitsRom(2,host));
        }
        assertFalse(SfcPlaybackMode.checksState(2));
    }
    @Test void unknownModesFailClosedInEveryDecision(){
        for(int mode:new int[]{-1,4,255,Integer.MAX_VALUE}){
            assertThrows(IllegalArgumentException.class,()->SfcPlaybackMode.checked(mode));
            assertThrows(IllegalArgumentException.class,()->SfcPlaybackMode.receivesMedia(mode,true));
            assertThrows(IllegalArgumentException.class,()->SfcPlaybackMode.permitsRom(mode,false));
            assertThrows(IllegalArgumentException.class,()->SfcPlaybackMode.checksState(mode));
        }
    }
    @Test void netplayRunsBothCoresWithoutOldStateScheduler(){
        for(boolean host:new boolean[]{false,true}){
            assertEquals(3,SfcPlaybackMode.checked(3));
            assertFalse(SfcPlaybackMode.receivesMedia(3,host));
            assertTrue(SfcPlaybackMode.permitsRom(3,host));
        }
        assertFalse(SfcPlaybackMode.checksState(3));
    }
}
