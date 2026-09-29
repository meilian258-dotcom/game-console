package cn.piq.fcarcade.cabinet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetConfigureIntentTest {
    @Test void exactIntentIsConsumedOnlyOnce(){var q=new CabinetConfigureIntent<String>();var c=new Object();assertTrue(q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50));assertTrue(q.consume(c,"target","piq:sfc",149));assertFalse(q.consume(c,"target","piq:sfc",149));}
    @Test void expiryBoundaryAndCancellationAreFailClosed(){var q=new CabinetConfigureIntent<String>();var c=new Object();q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50);assertFalse(q.consume(c,"target","piq:sfc",150));q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50);q.clear();assertFalse(q.consume(c,"target","piq:sfc",101));}
    @Test void differentConnectionTargetOrBackendCannotConfigure(){for(int mismatch=0;mismatch<3;mismatch++){var q=new CabinetConfigureIntent<String>();var c=new Object();q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50);assertFalse(q.consume(mismatch==0?new Object():c,mismatch==1?"other":"target",mismatch==2?"piq:mame":"piq:sfc",101));assertFalse(q.consume(c,"target","piq:sfc",102));}}
    @Test void repeatedMenuTokenCannotBeRearmed(){var q=new CabinetConfigureIntent<String>();var c=new Object();var token=UUID.randomUUID();assertTrue(q.arm(c,"target","piq:sfc",token,100,50));assertTrue(q.consume(c,"target","piq:sfc",101));assertFalse(q.arm(c,"target","piq:sfc",token,102,50));assertFalse(q.consume(c,"target","piq:sfc",103));}
    @Test void connectionChangeAndTickExpiryClearPendingIntent(){var q=new CabinetConfigureIntent<String>();var c=new Object();q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50);q.expire(new Object(),101);assertFalse(q.consume(c,"target","piq:sfc",102));q.arm(c,"target","piq:sfc",UUID.randomUUID(),100,50);q.expire(c,150);assertFalse(q.consume(c,"target","piq:sfc",151));}
    @Test void invalidArmAndNanoTimeWrapDoNotAuthorize(){var q=new CabinetConfigureIntent<String>();var c=new Object();assertFalse(q.arm(null,"target","piq:sfc",UUID.randomUUID(),0,5));assertFalse(q.arm(c,"target","piq:sfc",UUID.randomUUID(),0,0));long now=Long.MAX_VALUE-10;q.arm(c,"target","piq:sfc",UUID.randomUUID(),now,20);assertTrue(q.consume(c,"target","piq:sfc",Long.MIN_VALUE+5));}
}
