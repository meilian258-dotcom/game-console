package cn.piq.fcarcade.cabinet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSyncPolicyTest {
    private static final String ROM="1".repeat(64), CONTENT="2".repeat(64), INITIAL="3".repeat(64), OTHER="4".repeat(64);
    private static final String BUILD="lab-fixed-dll-helper-profile-v1";
    private static CabinetSyncPolicy.Identity identity(String rom,String content,String build,int fps,String initial){return new CabinetSyncPolicy.Identity(rom,content,build,fps,initial);}
    private static CabinetSyncPolicy.Identity host(){return identity(ROM,CONTENT,BUILD,59186,INITIAL);}

    @Test void strictDefaultRetainsInitialHashRequirement(){
        var p=new CabinetSyncPolicy(2,null);assertFalse(p.hostSnapshot());assertNull(p.expectedCompatibility());
        assertTrue(p.acceptsHost(host()));assertTrue(p.acceptsGuest(host(),host()));
        assertFalse(p.acceptsGuest(host(),identity(ROM,CONTENT,BUILD,59186,OTHER)));
    }
    @Test void onlyPinnedSnapshotPolicyAllowsDifferentColdBootBytes(){
        var p=new CabinetSyncPolicy(2,BUILD);assertTrue(p.hostSnapshot());
        assertTrue(p.acceptsGuest(host(),identity(ROM,CONTENT,BUILD,59186,OTHER)));
    }
    @Test void firstHostCannotChooseAnUnregisteredCoreFingerprint(){
        var p=new CabinetSyncPolicy(2,BUILD);assertTrue(p.acceptsHost(host()));
        assertFalse(p.acceptsHost(identity(ROM,CONTENT,BUILD+"-wrong",59186,INITIAL)));
        assertFalse(p.acceptsHost(null));
    }
    @Test void matchingMaliciousPeerFingerprintsDoNotBypassServerPin(){
        var p=new CabinetSyncPolicy(2,BUILD);var wrong=identity(ROM,CONTENT,"other-core",59186,INITIAL);
        assertFalse(p.acceptsGuest(wrong,wrong));
    }
    @Test void snapshotAdmissionStillRejectsDifferentRom(){assertFalse(new CabinetSyncPolicy(2,BUILD).acceptsGuest(host(),identity(OTHER,CONTENT,BUILD,59186,OTHER)));}
    @Test void snapshotAdmissionStillRejectsDifferentBiosOrManifest(){assertFalse(new CabinetSyncPolicy(2,BUILD).acceptsGuest(host(),identity(ROM,OTHER,BUILD,59186,OTHER)));}
    @Test void snapshotAdmissionStillRejectsDifferentClock(){assertFalse(new CabinetSyncPolicy(2,BUILD).acceptsGuest(host(),identity(ROM,CONTENT,BUILD,60000,OTHER)));}
    @Test void snapshotAdmissionStillRejectsDifferentGuestCore(){assertFalse(new CabinetSyncPolicy(2,BUILD).acceptsGuest(host(),identity(ROM,CONTENT,"other",59186,OTHER)));}
    @Test void strictPeersAlsoNeedAllIdentityFields(){
        var p=new CabinetSyncPolicy(2,null);
        assertFalse(p.acceptsGuest(host(),identity(OTHER,CONTENT,BUILD,59186,INITIAL)));
        assertFalse(p.acceptsGuest(host(),identity(ROM,OTHER,BUILD,59186,INITIAL)));
        assertFalse(p.acceptsGuest(host(),identity(ROM,CONTENT,"other",59186,INITIAL)));
        assertFalse(p.acceptsGuest(host(),identity(ROM,CONTENT,BUILD,60000,INITIAL)));
    }
    @Test void absentHelloNeverGrantsRestore(){var p=new CabinetSyncPolicy(2,BUILD);assertFalse(p.acceptsGuest(null,host()));assertFalse(p.acceptsGuest(host(),null));}
    @Test void policiesAndIdentitiesHaveFiniteWireBounds(){
        for(int n:new int[]{-1,0,5,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new CabinetSyncPolicy(n,BUILD));
        for(String s:new String[]{""," ","\n","x".repeat(257)})assertThrows(IllegalArgumentException.class,()->new CabinetSyncPolicy(2,s));
        assertThrows(IllegalArgumentException.class,()->identity(ROM,CONTENT,BUILD,39999,INITIAL));
        assertThrows(IllegalArgumentException.class,()->identity(ROM,CONTENT,BUILD,80001,INITIAL));
        assertThrows(IllegalArgumentException.class,()->identity("bad",CONTENT,BUILD,59186,INITIAL));
        assertThrows(IllegalArgumentException.class,()->identity(ROM,"bad",BUILD,59186,INITIAL));
        assertThrows(IllegalArgumentException.class,()->identity(ROM,CONTENT,BUILD,59186,"bad"));
    }
    @Test void experimentalTwoPortsRejectLinkedThreeAndFourWithoutTruncation(){
        var p=new CabinetSyncPolicy(2,BUILD);
        assertEquals(2,CabinetSeats.capacity(p.maxPlayers(),false,true,false));
        assertEquals(2,CabinetSeats.capacity(p.maxPlayers(),true,false,false));
        assertEquals(0,CabinetSeats.capacity(p.maxPlayers(),true,true,false));
        assertEquals(0,CabinetSeats.capacity(p.maxPlayers(),true,false,true));
        assertEquals(0,CabinetSeats.capacity(p.maxPlayers(),true,true,true));
        assertEquals(3,CabinetSeats.capacity(4,true,true,false));
        assertEquals(3,CabinetSeats.capacity(4,true,false,true));
        assertEquals(4,CabinetSeats.capacity(4,true,true,true));
    }
    @Test void snapshotIdentityAcceptanceIsNotAnInputGrant(){
        var p=new CabinetSyncPolicy(2,BUILD);assertTrue(p.acceptsGuest(host(),identity(ROM,CONTENT,BUILD,59186,OTHER)));
        UUID room=UUID.randomUUID(),member=UUID.randomUUID(),token=UUID.randomUUID();Object source=new Object();
        var gate=new CabinetSyncGate(room,member,source,false);
        assertFalse(gate.input(room,member,1,source));assertTrue(gate.begin(token,1800,900));
        assertFalse(gate.acknowledge(token,1800,1800,false,10));
        assertFalse(gate.input(room,member,1,source));
        assertTrue(gate.acknowledge(token,1800,1800,true,11));
        assertTrue(gate.input(room,member,1,source));
        assertFalse(gate.input(room,member,1,new Object()));
    }
    @Test void snapshotPolicyNeverLetsGuestRestoreTheHost(){
        UUID room=UUID.randomUUID(),member=UUID.randomUUID();var gate=new CabinetSyncGate(room,member,new Object(),true);
        gate.activateHost();assertFalse(gate.begin(UUID.randomUUID(),0,900));assertTrue(gate.active());
    }
}
