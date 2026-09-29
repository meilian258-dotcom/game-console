package cn.piq.nativearcade.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeArcadeButtonsTest {
    @Test void numberedSixButtonsMatchPinnedMameBaseline(){int[] host={0,1,8,9,10,11},mame={0,8,1,9,10,11};for(int i=0;i<6;i++)assertEquals(1<<mame[i],NativeArcadeButtons.toMame(1<<host[i]));}
    @Test void directionsCoinStartAndExtendedBitsNeverMove(){for(int bit:new int[]{2,3,4,5,6,7,12,13,14,15})assertEquals(1<<bit,NativeArcadeButtons.toMame(1<<bit));}
    @Test void allMasksAreLosslessPermutationsAndReleaseAllIsZero(){for(int mask=0;mask<=0xffff;mask++){int converted=NativeArcadeButtons.toMame(mask);assertEquals(Integer.bitCount(mask),Integer.bitCount(converted));assertEquals(mask,NativeArcadeButtons.toMame(converted));}assertEquals(0,NativeArcadeButtons.toMame(0));}
    @Test void invalidMasksAreRejectedBeforeNativeCallback(){assertThrows(IllegalArgumentException.class,()->NativeArcadeButtons.toMame(-1));assertThrows(IllegalArgumentException.class,()->NativeArcadeButtons.toMame(65536));}
}
