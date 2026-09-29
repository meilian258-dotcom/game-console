package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetVideoGeometryTest {
    /** Optional isolated runner for the pure tests, without Gradle or Minecraft. */
    public static void main(String[] args)throws Exception {
        var suite=new CabinetVideoGeometryTest();int count=0;
        for(var method:CabinetVideoGeometryTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){
            try{method.invoke(suite);count++;}catch(java.lang.reflect.InvocationTargetException error){
                throw new AssertionError(method.getName(),error.getCause());
            }
        }
        if(count!=11)throw new AssertionError("Unexpected test count: "+count);
        System.out.println("CabinetVideoGeometry: "+count+" checks passed; no Minecraft or emulator launched.");
    }
    private static final double EPS=1e-10;
    private static Point minus(Point a,Point b){return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    private static double dot(Point a,Point b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    private static Point cross(Point a,Point b){return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private static void point(Point expected,Point actual){assertEquals(expected.x(),actual.x(),EPS);assertEquals(expected.y(),actual.y(),EPS);assertEquals(expected.z(),actual.z(),EPS);}
    private static Point[] points(ScreenQuad q){return new Point[]{q.lowerMinX(),q.lowerMaxX(),q.upperMaxX(),q.upperMinX()};}

    @Test void readsRealFourThreeSingleAndUserDualGlassWithoutPreCropping() {
        for(int facing=0;facing<4;facing++){
            var single=CabinetVideoGeometry.frame(false,facing,4.0/3,0);
            var dual=CabinetVideoGeometry.frame(true,facing,4.0/3,0);
            assertSame(RocketArcadeGeometry.screen(facing),single.glass());
            assertSame(DualCabinetGeometry.screen(facing),dual.glass());
            assertSame(single.glass(),single.image());assertSame(dual.glass(),dual.image());
            assertEquals(10.52/16,single.glass().width(),EPS);assertEquals(7.89/16,single.glass().height(),EPS);
            assertEquals(18D/16,dual.glass().width(),EPS);assertEquals(13.5/16,dual.glass().height(),EPS);
        }
    }
    @Test void wideContentOnRefinedGlassLeavesEqualUncroppedBars() {
        for(int facing=0;facing<4;facing++){
            var frame=CabinetVideoGeometry.frame(true,facing,16.0/9,0);
            assertEquals(1.6875/16,(frame.glass().height()-frame.image().height())/2,EPS);
            assertEquals(frame.glass().width(),frame.image().width(),EPS);
            assertEquals(16.0/9,frame.image().aspectRatio(),EPS);
        }
    }
    @Test void wideContentOnSingleGlassHasEqualTopAndBottomLetterboxing() {
        var frame=CabinetVideoGeometry.frame(false,0,16.0/9,0);
        assertEquals(frame.glass().width(),frame.image().width(),EPS);
        assertEquals(frame.glass().width()*9/16,frame.image().height(),EPS);
        double bar=(frame.glass().height()-frame.image().height())/2;
        assertTrue(bar>0);assertEquals(bar,frame.image().lowerMaxX().distanceTo(frame.glass().lowerMaxX()),EPS);
        assertEquals(bar,frame.image().upperMaxX().distanceTo(frame.glass().upperMaxX()),EPS);
    }
    @Test void rawDisplayAspectIsInvertedExactlyOnceForOddContentRotations() {
        // A raw 260x224 diagnostic can declare DAR 4:3; neither its pixel ratio nor a pre-inverted ratio is used.
        for(boolean dual:new boolean[]{false,true})for(int rotation=0;rotation<4;rotation++){
            var frame=CabinetVideoGeometry.frame(dual,0,4.0/3,rotation);
            double expected=(rotation&1)==0?4.0/3:3.0/4;
            assertEquals(4.0/3,frame.rawAspect(),0);assertEquals(expected,frame.displayAspect(),EPS);
            assertEquals(expected,frame.image().aspectRatio(),EPS);
            if((rotation&1)!=0)assertNotEquals(224.0/260,frame.displayAspect(),1e-4);
        }
    }
    @Test void allFitsStayOnOriginalTiltedPlaneCenteredAndUncropped() {
        double[] aspects={.25,.5,.75,1,8.0/7,4.0/3,16.0/9,2,4,32};
        for(boolean dual:new boolean[]{false,true})for(int facing=0;facing<4;facing++)
            for(int rotation=-5;rotation<=8;rotation++)for(double aspect:aspects){
                var frame=CabinetVideoGeometry.frame(dual,facing,aspect,rotation);var glass=frame.glass();var fitted=frame.image();
                point(glass.center(),fitted.center());point(glass.normal(),fitted.normal());
                assertEquals(frame.displayAspect(),fitted.aspectRatio(),1e-9);
                assertTrue(fitted.width()<=glass.width()+EPS);assertTrue(fitted.height()<=glass.height()+EPS);
                assertTrue(Math.abs(fitted.width()-glass.width())<EPS||Math.abs(fitted.height()-glass.height())<EPS);
                var across=minus(glass.lowerMaxX(),glass.lowerMinX());var up=minus(glass.upperMinX(),glass.lowerMinX());
                for(var p:points(fitted)){
                    var delta=minus(p,glass.lowerMinX());double x=dot(delta,across)/dot(across,across),y=dot(delta,up)/dot(up,up);
                    assertTrue(x>=-EPS&&x<=1+EPS);assertTrue(y>=-EPS&&y<=1+EPS);assertEquals(0,dot(delta,glass.normal()),EPS);
                }
                var uv=new HashSet<String>();for(var vertex:frame.vertices())uv.add(vertex.u()+":"+vertex.v());
                assertEquals(4,uv.size());assertTrue(uv.containsAll(java.util.Set.of("0.0:0.0","0.0:1.0","1.0:0.0","1.0:1.0")));
            }
    }
    @Test void facingRotatesOnlyPhysicalGeometryAndNeverContentUvs() {
        for(boolean dual:new boolean[]{false,true})for(int rotation=0;rotation<4;rotation++){
            var north=CabinetVideoGeometry.frame(dual,0,8.0/7,rotation);
            for(int facing=1;facing<4;facing++){
                var other=CabinetVideoGeometry.frame(dual,facing,8.0/7,rotation);
                for(int i=0;i<4;i++){
                    point(RocketArcadeGeometry.rotate(north.vertices().get(i).point(),facing),other.vertices().get(i).point());
                    assertEquals(north.vertices().get(i).u(),other.vertices().get(i).u());
                    assertEquals(north.vertices().get(i).v(),other.vertices().get(i).v());
                }
            }
        }
    }
    @Test void inverseUvRotationMatchesNativePresentationAtEveryTextureCorner() {
        float[][] displayed={{0,1},{1,1},{1,0},{0,0}};
        float[][][] expected={{{0,1},{1,1},{1,0},{0,0}},{{0,0},{0,1},{1,1},{1,0}},
                {{1,0},{0,0},{0,1},{1,1}},{{1,1},{1,0},{0,0},{0,1}}};
        for(int rotation=0;rotation<4;rotation++)for(int i=0;i<4;i++){
            var uv=CabinetVideoGeometry.textureUv(displayed[i][0],displayed[i][1],rotation);
            assertEquals(expected[rotation][i][0],uv.u(),0);assertEquals(expected[rotation][i][1],uv.v(),0);
            var vertex=CabinetVideoGeometry.frame(true,0,4.0/3,rotation).vertices().get(i);
            assertEquals(uv.u(),vertex.u(),0);assertEquals(uv.v(),vertex.v(),0);
        }
    }
    @Test void normalsPreserveTwentyTwoAndAHalfDegreeTiltAndFrontWinding() {
        for(boolean dual:new boolean[]{false,true})for(int facing=0;facing<4;facing++){
            var frame=CabinetVideoGeometry.frame(dual,facing,1,0);var n=frame.image().normal();
            assertEquals(Math.sin(Math.PI/8),n.y(),EPS);assertEquals(1,dot(n,n),EPS);
            var v=frame.vertices();var edge1=minus(v.get(1).point(),v.get(0).point());var edge2=minus(v.get(2).point(),v.get(1).point());
            assertEquals(0,dot(edge1,n),EPS);assertEquals(0,dot(edge2,n),EPS);assertTrue(dot(cross(edge1,edge2),n)>0);
        }
    }
    @Test void repeatedFramesReuseBoundedImmutableCacheAndNormalizeTurns() {
        var frame=CabinetVideoGeometry.frame(true,-1,4.0/3,-1);
        assertSame(frame,CabinetVideoGeometry.frame(true,3,4.0/3,3));
        assertThrows(UnsupportedOperationException.class,()->frame.vertices().clear());
        var changed=CabinetVideoGeometry.frame(true,3,1,3);assertNotSame(frame,changed);
        assertSame(changed,CabinetVideoGeometry.frame(true,7,1,7));
    }
    @Test void rejectsInvalidMetadataBeforeItCanCreateNonFiniteVertices() {
        for(double aspect:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,33})
            assertThrows(IllegalArgumentException.class,()->CabinetVideoGeometry.frame(false,0,aspect,0));
        assertThrows(IllegalArgumentException.class,()->CabinetVideoGeometry.frame(false,0,Double.MIN_VALUE,1));
        assertThrows(IllegalArgumentException.class,()->CabinetVideoGeometry.frame(false,0,1e-100,0));
        assertThrows(IllegalArgumentException.class,()->CabinetVideoGeometry.textureUv(Float.NaN,0,0));
        assertThrows(IllegalArgumentException.class,()->CabinetVideoGeometry.textureUv(0,2,0));
    }
    @Test void newRendererIsScopedToAnExternalCurrentTargetAndDoesNotOwnTextureLifetime()throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetVideoDisplay.java"));
        assertTrue(source.contains("AFTER_BLOCK_ENTITIES"));assertTrue(source.contains("!target.matches(minecraft.level)"));
        assertTrue(source.contains("CabinetBackends.NES.equals(cabinet.cabinetBackend())"));
        assertTrue(source.contains("frame.vertices()"));assertTrue(source.contains("finally{poses.popPose();}"));
        assertFalse(source.contains("DynamicTexture"));assertFalse(source.contains("texture.upload()"));
        assertFalse(source.contains("ClientArcadeSession"));assertFalse(source.contains("CrtScanlineVertexConsumer"));
    }
}
