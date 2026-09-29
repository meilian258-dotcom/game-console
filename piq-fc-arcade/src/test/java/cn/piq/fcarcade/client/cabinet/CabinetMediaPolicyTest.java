package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetMediaPolicyTest {
    @Test void automaticStartsConservativelyAndRequiresThreeHealthySamples(){var policy=new CabinetMediaPolicy();assertEquals(20,policy.target());assertEquals(20,policy.sample(true,30,10000,2,0,0));assertEquals(20,policy.sample(true,30,10000,2,0,0));assertEquals(30,policy.sample(true,30,10000,2,0,0));}
    @Test void NetworkBackpressureImmediatelyReturnsToTwentyAndResetsRecovery(){var policy=new CabinetMediaPolicy();for(int i=0;i<3;i++)policy.sample(true,30,10000,2,0,0);assertEquals(20,policy.sample(true,30,10000,2,0,1));assertEquals(20,policy.sample(true,30,10000,2,0,0));assertEquals(20,policy.sample(true,30,10000,2,0,0));assertEquals(30,policy.sample(true,30,10000,2,0,0));}
    @Test void ExpensiveEncodingHighPayloadOrLongQueueNeverPromotes(){for(int mode=0;mode<3;mode++){var policy=new CabinetMediaPolicy();for(int i=0;i<30;i++)assertEquals(20,policy.sample(true,30,mode==0?30000:10000,mode==1?11:2,mode==2?3:0,0));}}
    @Test void FixedPreferenceIsExplicitAndDoesNotClaimBandwidthIsAvailable(){var policy=new CabinetMediaPolicy();assertEquals(30,policy.sample(false,30,90000,200,8,10));assertEquals(20,policy.sample(false,20,90000,200,8,10));assertEquals(20,policy.sample(false,60,100,1,0,0));}
}
