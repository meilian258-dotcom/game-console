package cn.piq.fcarcade.client.watch;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchAdmissionPolicyTest {
    private static final WatchAdmissionPolicy.Claim JNI=new WatchAdmissionPolicy.Claim(true,true);
    private static final WatchAdmissionPolicy.Claim PROCESS=new WatchAdmissionPolicy.Claim(true,false);
    private static final WatchAdmissionPolicy.Claim UNKNOWN=new WatchAdmissionPolicy.Claim(true,null);
    private static final WatchAdmissionPolicy.Claim MEDIA=new WatchAdmissionPolicy.Claim(false,null);
    private static final class Scheduler {
        final List<WatchAdmissionPolicy.Claim> active=new ArrayList<>();
        int preparations;
        boolean offer(WatchAdmissionPolicy.Claim next){
            if(!WatchAdmissionPolicy.allows(next,active,4,false,false))return false;
            active.add(next);preparations++;return true;
        }
    }
    @Test void twoProcessOffersInOneBatchPrepareOnlyTheFirstSource(){
        var scheduler=new Scheduler();
        assertTrue(scheduler.offer(PROCESS));
        for(int repeat=0;repeat<8;repeat++)assertFalse(scheduler.offer(PROCESS));
        assertEquals(1,scheduler.preparations);
        assertEquals(List.of(PROCESS),scheduler.active);
    }
    @Test void processOfferCannotDownloadOrDisplaceExistingJni(){
        var scheduler=new Scheduler();
        assertTrue(scheduler.offer(JNI));assertFalse(scheduler.offer(PROCESS));
        assertEquals(1,scheduler.preparations);assertEquals(List.of(JNI),scheduler.active);
    }
    @Test void jniOfferCannotDownloadOrDisplaceExistingProcess(){
        var scheduler=new Scheduler();
        assertTrue(scheduler.offer(PROCESS));assertFalse(scheduler.offer(JNI));
        assertEquals(1,scheduler.preparations);assertEquals(List.of(PROCESS),scheduler.active);
    }
    @Test void oldJniFourSlotBudgetAndUnknownProviderAdmissionRemainUnchanged(){
        var scheduler=new Scheduler();
        assertTrue(scheduler.offer(JNI));assertTrue(scheduler.offer(UNKNOWN));
        assertTrue(scheduler.offer(JNI));assertTrue(scheduler.offer(UNKNOWN));
        assertFalse(scheduler.offer(JNI));assertEquals(4,scheduler.preparations);
        assertFalse(WatchAdmissionPolicy.allows(JNI,List.of(JNI),1,false,false));
        assertFalse(WatchAdmissionPolicy.allows(JNI,List.of(),0,false,false));
        assertFalse(WatchAdmissionPolicy.allows(JNI,List.of(JNI,JNI,JNI,JNI),99,false,false));
    }
    @Test void mediaAndProcessRemainIdleSingleSourceOnly(){
        assertFalse(WatchAdmissionPolicy.allows(PROCESS,List.of(),4,true,false));
        assertFalse(WatchAdmissionPolicy.allows(MEDIA,List.of(),4,true,false));
        assertTrue(WatchAdmissionPolicy.allows(JNI,List.of(),4,true,false));
        assertFalse(WatchAdmissionPolicy.allows(MEDIA,List.of(JNI),4,false,false));
        assertFalse(WatchAdmissionPolicy.allows(JNI,List.of(MEDIA),4,false,false));
        assertTrue(WatchAdmissionPolicy.allows(MEDIA,List.of(),0,false,false));
    }
    @Test void aClosingProcessMustReleaseBeforeAnotherNetplayObserverIsAdmitted(){
        assertFalse(WatchAdmissionPolicy.allows(PROCESS,List.of(),4,false,true));
        assertFalse(WatchAdmissionPolicy.allows(JNI,List.of(),4,false,true));
        assertTrue(WatchAdmissionPolicy.allows(PROCESS,List.of(),4,false,false));
    }
    @Test void fixedTrustedDeclarationMustMatchLoadedProfile(){
        assertEquals(Boolean.TRUE,NetplayWatchContent.Execution.JNI.nativeMode());
        assertEquals(Boolean.FALSE,NetplayWatchContent.Execution.PROCESS.nativeMode());
        assertNull(NetplayWatchContent.Execution.UNDECLARED.nativeMode());
        assertDoesNotThrow(()->NetplayWatchContent.Execution.JNI.verify(true));
        assertDoesNotThrow(()->NetplayWatchContent.Execution.PROCESS.verify(false));
        assertThrows(IllegalStateException.class,()->NetplayWatchContent.Execution.JNI.verify(false));
        assertThrows(IllegalStateException.class,()->NetplayWatchContent.Execution.PROCESS.verify(true));
        assertDoesNotThrow(()->NetplayWatchContent.Execution.UNDECLARED.verify(true));
        assertDoesNotThrow(()->NetplayWatchContent.Execution.UNDECLARED.verify(false));
    }
    @Test void legacyBinaryRegistrationSignatureRemainsAvailable()throws Exception{
        assertNotNull(NetplayWatchContent.class.getMethod("register",net.minecraft.resources.ResourceLocation.class,NetplayWatchContent.Provider.class));
        assertNotNull(NetplayWatchContent.class.getMethod("register",net.minecraft.resources.ResourceLocation.class,
                NetplayWatchContent.Execution.class,NetplayWatchContent.Provider.class));
    }
    private static String source(String name)throws Exception{
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+name+".java")).replaceAll("\\s+","");
    }
    @Test void fcDeclaresModeAndAdmissionRunsBeforeAnyContentPreparation()throws Exception{
        String display=source("FcHomeWatchDisplay"),client=source("watch/WatchClient");
        assertTrue(display.contains("jni?NetplayWatchContent.Execution.JNI:NetplayWatchContent.Execution.PROCESS"));
        assertTrue(client.contains("execution=grant==null?NetplayWatchContent.Execution.UNDECLARED:NetplayWatchContent.execution(source.provider());nativeMode=execution.nativeMode();"));
        assertTrue(client.contains("voidnetplay(WatchNetwork.NetplayStartgrant){if(admit(grant.watch(),grant)!=null)prepareNext();}"));
        String admission=client.substring(client.indexOf("private static Receiver admit".replace(" ","")),client.indexOf("@Overridepublicvoidstart("));
        assertTrue(admission.indexOf("WatchAdmissionPolicy.allows(")<admission.indexOf("RECEIVERS.put("));
        assertFalse(admission.contains("NetplayWatchContent.prepare("));
        assertFalse(admission.contains("nativeFailure("));
        assertTrue(admission.contains("HISTORY.retire(start.lease());release(r);returnnull;"));
    }
    @Test void mismatchedProfileFailsBeforeOpenAndClosesOnlyItsOwnReceiver()throws Exception{
        String client=source("watch/WatchClient");
        String prepare=client.substring(client.indexOf("privatestaticvoidprepareNext()"),client.indexOf("privatestaticvoidnativeFailure("));
        assertTrue(prepare.indexOf("r.execution.verify(actualJni);")<prepare.indexOf("r.netplay=captured.open("));
        assertTrue(prepare.contains("catch(Exception|LinkageErrorbad){nativeFailure(r,bad);}"));
        String failure=client.substring(client.indexOf("privatestaticvoidnativeFailure("),client.indexOf("publicstaticvoidcontrolStarting()"));
        assertTrue(failure.contains("close(r,true);"));assertFalse(failure.contains("closeAll("));
    }
}
