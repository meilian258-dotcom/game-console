package cn.piq.mdhome;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdInteractionPolicyTest {
    @Test void sixBlockBoundaryIsInclusiveAndMalformedDistancesFailClosed(){
        for(double d:new double[]{0,1,35.99,36})assertTrue(MdInteractionPolicy.inControllerRange(d));
        for(double d:new double[]{36.0001,100,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertFalse(MdInteractionPolicy.inControllerRange(d));
    }
    @Test void loanUsesEmptyHandWithoutReplacingAnItem(){
        assertEquals(0,MdInteractionPolicy.emptyHand(true,true));
        assertEquals(0,MdInteractionPolicy.emptyHand(true,false));
        assertEquals(1,MdInteractionPolicy.emptyHand(false,true));
        assertEquals(-1,MdInteractionPolicy.emptyHand(false,false));
    }
}
