// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.layout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeVideoPresentationTest {
    @Test void rotatesCounterClockwiseExactlyOnce(){
        assertArrayEquals(new float[]{1,0},NativeVideoPresentation.textureUv(0,0,1));
        assertArrayEquals(new float[]{1,1},NativeVideoPresentation.textureUv(1,0,1));
        assertArrayEquals(new float[]{0,1},NativeVideoPresentation.textureUv(1,1,1));
        assertArrayEquals(new float[]{0,0},NativeVideoPresentation.textureUv(0,1,1));
    }
    @Test void keepsAllCornersAndReturnsAfterFourRotations(){for(float u:new float[]{0,1})for(float v:new float[]{0,1}){
        float[] p={u,v};for(int i=0;i<4;i++)p=NativeVideoPresentation.textureUv(p[0],p[1],1);assertArrayEquals(new float[]{u,v},p);
    }}
    @Test void aspectInvertsOnlyOnOddRotation(){for(int r=0;r<4;r++)assertEquals(r%2==0?4F/3F:3F/4F,NativeVideoPresentation.displayAspect(4F/3F,r),.0001);}
    @Test void invalidGeometryFailsClosed(){assertThrows(IllegalArgumentException.class,()->NativeVideoPresentation.displayAspect(Float.NaN,0));assertThrows(IllegalArgumentException.class,()->NativeVideoPresentation.displayAspect(0,1));}
}
