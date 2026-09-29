package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CabinetVisualInputsTest {
    private CabinetPeerInputs occupied(){
        var inputs=new CabinetPeerInputs();
        for(int port=0;port<4;port++){
            var id=new UUID(0,port+1);assertTrue(inputs.seat(id,port,true));
            assertTrue(inputs.input(id,port,0,1<<port));
        }
        return inputs;
    }
    @Test void hostProjectsPrimaryAndSecondaryWithoutMutatingInput(){
        var inputs=occupied();
        assertArrayEquals(new int[]{32,2},inputs.visualPair(0,0,32,true));
        assertArrayEquals(new int[]{4,8},inputs.visualPair(2,0,32,true));
        assertEquals(1,inputs.mask(0));assertEquals(4,inputs.mask(2));
    }
    @Test void guestShowsOwnSeatNotInventedRemoteButtons(){
        var inputs=occupied();
        assertArrayEquals(new int[]{0,0},inputs.visualPair(0,3,512,false));
        assertArrayEquals(new int[]{0,512},inputs.visualPair(2,3,512,false));
        assertArrayEquals(new int[]{256,0},inputs.visualPair(2,2,256,false));
    }
    @Test void callerCannotMutatePeerState(){
        var inputs=occupied();var pair=inputs.visualPair(2,0,0,true);pair[0]=4095;
        assertEquals(4,inputs.mask(2));
    }
    @Test void resetOrDepartedSeatDoesNotStayPressed(){
        var inputs=occupied();assertTrue(inputs.seat(new UUID(0,3),2,false));
        assertArrayEquals(new int[]{0,8},inputs.visualPair(2,0,0,true));
        assertArrayEquals(new int[]{0,0},inputs.visualPair(2,3,0,false));
    }
    @Test void malformedProjectionIsNeutral(){
        var inputs=occupied();
        for(int first:new int[]{-1,1,3,4})assertArrayEquals(new int[2],inputs.visualPair(first,0,1,true));
        assertArrayEquals(new int[2],inputs.visualPair(0,4,1,true));
        assertArrayEquals(new int[2],inputs.visualPair(0,0,4096,true));
    }
}
