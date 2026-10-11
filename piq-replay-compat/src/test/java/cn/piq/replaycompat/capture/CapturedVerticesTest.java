package cn.piq.replaycompat.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import static cn.piq.replaycompat.capture.CaptureAssertions.*;

class CapturedVerticesTest {
    public static void main(String[] args) {
        var tests=new CapturedVerticesTest();
        tests.preservesRealDrawAndSnapshotsFinalAttributesInWorldCoordinates();
        tests.dropsOverflowWithoutChangingOriginalDraw();
        tests.dropsIncompleteAndNonFiniteGeometry();
        tests.dropsOutOfContractVerticesWithoutBreakingDelegate();
        System.out.println("CapturedVerticesTest: 4 behavioral cases passed");
    }
    static final class Sink implements VertexConsumer {
        int vertices,colors,uvs,normals;
        public VertexConsumer addVertex(float x,float y,float z){vertices++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){colors++;return this;}
        public VertexConsumer setUv(float u,float v){uvs++;return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){normals++;return this;}
    }
    void preservesRealDrawAndSnapshotsFinalAttributesInWorldCoordinates() {
        var sink=new Sink();var capture=new CapturedVertices(sink,100,64,-30);
        for(int i=0;i<4;i++)capture.addVertex(i,2,3).setColor(17,34,51,68).setUv(i/3f,.5f).setUv1(0,0).setUv2(240,240).setNormal(0,0,-1);
        var result=capture.finish();assertEquals(4,result.size());
        assertEquals(103,result.getLast().x());assertEquals(66,result.getLast().y());assertEquals(-27,result.getLast().z());
        assertEquals(0x44112233,result.getFirst().color());assertEquals(1,result.getLast().u());assertEquals(.5f,result.getLast().v());
        assertEquals(4,sink.vertices);assertEquals(4,sink.colors);assertEquals(4,sink.uvs);assertEquals(4,sink.normals);
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    void dropsOverflowWithoutChangingOriginalDraw() {
        var sink=new Sink();var capture=new CapturedVertices(sink,0,0,0);
        for(int i=0;i<4100;i++)capture.addVertex(0,0,0).setUv(0,0);
        assertTrue(capture.finish().isEmpty());assertEquals(4100,sink.vertices);
    }
    void dropsIncompleteAndNonFiniteGeometry() {
        var capture=new CapturedVertices(new Sink(),0,0,0);capture.addVertex(0,0,0);
        assertTrue(capture.finish().isEmpty());
        var bad=new CapturedVertices(new Sink(),0,0,0);
        for(int i=0;i<4;i++)bad.addVertex(0,0,0).setUv(Float.NaN,0);
        assertTrue(bad.finish().isEmpty());
    }
    void dropsOutOfContractVerticesWithoutBreakingDelegate() {
        for(double camera : new double[]{0,40_000_000}) {
            var sink=new Sink();var capture=new CapturedVertices(sink,camera,0,0);
            for(int i=0;i<4;i++) capture.addVertex(0,0,0).setUv(camera==0?17:0,0).setColor(1,2,3,4);
            assertTrue(capture.finish().isEmpty());assertEquals(4,sink.vertices);assertEquals(4,sink.uvs);assertEquals(4,sink.colors);
        }
    }
}
