package cn.piq.nativearcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The four cabinet facings never change libretro's independent counter-clockwise orientation. */
public class NativeVideoIntegrationTest {
    @Test void portraitCoreRemainsPortraitOnAllCabinetFacings() {
        for(int facing=0;facing<4;facing++) {
            float aspect=NativeVideoPresentation.displayAspect(4F/3F,1);
            var q=NativeCabinetLayout.frame(facing,aspect);
            assertEquals(.75,q.lowerMinX().distanceTo(q.lowerMaxX())/q.lowerMinX().distanceTo(q.upperMinX()),1e-6);
            assertEquals(NativeCabinetLayout.screen(facing).center().y(),q.center().y(),1e-10);
        }
    }
    @Test void rawQuadSourceUvIsViewerLeftNotWorldMinX() {
        var q=NativeCabinetLayout.screen(0);
        assertTrue(q.lowerMaxX().x()>q.lowerMinX().x());
        // Existing cabinet north faces -Z: high X is viewer-left. Source lower-left is U0 V1.
        assertArrayEquals(new float[]{0,1},NativeVideoPresentation.textureUv(0,1,0));
        assertEquals(.25,q.lowerMinX().x());assertEquals(1.75,q.lowerMaxX().x());
    }
    @Test void counterClockwiseRawCornerIdentitiesAreNotMirrored() {
        float[][][] expected={{{0,0},{1,0},{1,1},{0,1}},{{1,0},{1,1},{0,1},{0,0}},
                {{1,1},{0,1},{0,0},{1,0}},{{0,1},{0,0},{1,0},{1,1}}};
        float[][] displayed={{0,0},{1,0},{1,1},{0,1}};
        for(int r=0;r<4;r++)for(int c=0;c<4;c++)assertArrayEquals(expected[r][c],NativeVideoPresentation.textureUv(displayed[c][0],displayed[c][1],r));
    }
    @Test void screenHasExactlyOneNormalOffsetAndNoRawJsonYShift() {
        var q=NativeCabinetLayout.screen(0);var n=q.normal();
        assertEquals(16.55/16+n.y()*.0015,q.lowerMinX().y(),1e-12);
        assertEquals(5.0/16+n.z()*.0015,q.lowerMinX().z(),1e-12);
        assertTrue(q.upperMinX().y()<2.0); // Not plus .35 once again by the native video callback.
    }
    @Test void coreOrientationAndCabinetOrientationCommute() {
        for(int r=0;r<4;r++)for(int t=0;t<4;t++) {
            double aspect=NativeVideoPresentation.displayAspect(4F/3F,r);
            var original=NativeCabinetLayout.frame(0,aspect);var actual=NativeCabinetLayout.frame(t,aspect);
            var expected=RocketArcadeGeometry.rotate(original.lowerMaxX(),t);
            assertEquals(expected.x(),actual.lowerMaxX().x(),1e-10);
            assertEquals(expected.y(),actual.lowerMaxX().y(),1e-10);
            assertEquals(expected.z(),actual.lowerMaxX().z(),1e-10);
        }
    }
    @Test void sourceSampleCornersStayWithinEntireRawTexture() {
        for(int r=0;r<4;r++)for(float u:new float[]{0,.25F,.5F,1})for(float v:new float[]{0,.25F,.5F,1}){
            var uv=NativeVideoPresentation.textureUv(u,v,r);assertTrue(uv[0]>=0&&uv[0]<=1&&uv[1]>=0&&uv[1]<=1);
        }
    }
    public static void main(String[] args)throws Exception {
        var test=new NativeVideoIntegrationTest();int count=0;
        for(var m:test.getClass().getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(test);count++;}
        System.out.println("NATIVE_VIDEO_INTEGRATION_TESTS="+count+" PASS");
    }
}
