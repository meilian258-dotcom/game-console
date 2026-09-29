package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

public class CrtScanlineVertexConsumerTest {
    private static int assertions;
    public static void main(String[] args) {
        var probe = new CrtScanlineVertexConsumerTest();
        probe.replacesQuadWithSingleLayerKeepingUvsAlphaAndAttributes();
        probe.farAwayAndZeroViewportPassOriginalVerticesExactly();
        probe.behindEyeDisablesStripesAndConsumerCanReceiveNextQuad();
        probe.transformedCameraDistanceControlsDensity();
        System.out.println("CRT actual vertex consumer: 4 cases / " + assertions + " assertions passed");
    }
    private static void assertEquals(double expected, double actual) { assertEquals(expected,actual,0); }
    private static void assertEquals(double expected, double actual, double tolerance) {
        assertions++;
        if (!Double.isFinite(actual) || Math.abs(expected-actual)>tolerance)
            throw new AssertionError(expected+" != "+actual);
    }
    private static void assertArrayEquals(float[] expected,float[] actual) {assertArrayEquals(expected,actual,0);}
    private static void assertArrayEquals(float[] expected,float[] actual,float tolerance) {
        assertEquals(expected.length,actual.length);
        for(int i=0;i<expected.length;i++) assertEquals(expected[i],actual[i],tolerance);
    }
    void replacesQuadWithSingleLayerKeepingUvsAlphaAndAttributes() {
        Capture target = new Capture();
        var consumer = new CrtScanlineVertexConsumer(target, new Matrix4f(), 1200, 600);
        quad(consumer);
        assertEquals(960, target.vertices.size());
        for (int row = 0; row < 240; row++) {
            List<float[]> strip = target.vertices.subList(row * 4, row * 4 + 4);
            float lowV = CrtScanlinePattern.bottom(row), highV = CrtScanlinePattern.top(row);
            assertArrayEquals(new float[]{-1, 1 - 2 * lowV, 0}, xyz(strip.get(0)), 1e-6F);
            assertArrayEquals(new float[]{1, 1 - 2 * lowV, 0}, xyz(strip.get(1)), 1e-6F);
            assertArrayEquals(new float[]{1, 1 - 2 * highV, 0}, xyz(strip.get(2)), 1e-6F);
            assertArrayEquals(new float[]{-1, 1 - 2 * highV, 0}, xyz(strip.get(3)), 1e-6F);
            for (int i = 0; i < 4; i++) {
                float[] v = strip.get(i);
                assertEquals((row & 1) == 0 ? 255 : 184, v[3]);
                assertEquals(v[3], v[4]); assertEquals(v[3], v[5]);
                assertEquals(173, v[6]);
                assertEquals((i == 0 || i == 3) ? 0 : 1, v[7]);
                assertEquals(i < 2 ? lowV : highV, v[8], 1e-6F);
                assertEquals(5, v[9]); assertEquals(6, v[10]);
                assertEquals(240, v[11]); assertEquals(240, v[12]);
                assertEquals(0, v[13]); assertEquals(0, v[14]); assertEquals(1, v[15]);
            }
        }
    }

    void farAwayAndZeroViewportPassOriginalVerticesExactly() {
        for (int height : new int[]{0, 120, 240}) {
            Capture target = new Capture(), original = new Capture();
            quad(original);
            quad(new CrtScanlineVertexConsumer(target, new Matrix4f(), 1200, height));
            assertEquals(4, target.vertices.size());
            for (int i = 0; i < 4; i++) assertArrayEquals(original.vertices.get(i), target.vertices.get(i));
        }
    }

    void behindEyeDisablesStripesAndConsumerCanReceiveNextQuad() {
        Capture target = new Capture();
        var invalid = new CrtScanlineVertexConsumer(target, new Matrix4f().m33(-1), 1200, 600);
        quad(invalid); quad(invalid);
        assertEquals(8, target.vertices.size());
    }

    void transformedCameraDistanceControlsDensity() {
        Capture target = new Capture();
        var consumer = new CrtScanlineVertexConsumer(target,
                new Matrix4f().perspective((float) Math.toRadians(70), 2F, 0.1F, 100F), 1200, 600);
        Matrix4f pose = new Matrix4f().translate(0, 0, -20);
        for (float[] v : corners()) consumer.addVertex(pose, v[0], v[1], 0)
                .setColor(255,255,255,173).setUv(v[2],v[3]).setUv1(5,6).setUv2(240,240).setNormal(0,0,1);
        assertEquals(4, target.vertices.size());
        assertEquals(-20, target.vertices.get(0)[2]); // no double pose transform
    }

    private static float[] xyz(float[] v) { return new float[]{v[0],v[1],v[2]}; }
    private static float[][] corners() { return new float[][]{{-1,-1,0,1},{1,-1,1,1},{1,1,1,0},{-1,1,0,0}}; }
    private static void quad(VertexConsumer consumer) {
        for (float[] v : corners()) consumer.addVertex(v[0],v[1],0)
                .setColor(255,255,255,173).setUv(v[2],v[3]).setUv1(5,6).setUv2(240,240).setNormal(0,0,1);
    }

    private static final class Capture implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        float[] v;
        public VertexConsumer addVertex(float x,float y,float z) { v=new float[16]; vertices.add(v); v[0]=x;v[1]=y;v[2]=z;return this; }
        public VertexConsumer setColor(int r,int g,int b,int a) {v[3]=r;v[4]=g;v[5]=b;v[6]=a;return this;}
        public VertexConsumer setUv(float u,float w) {v[7]=u;v[8]=w;return this;}
        public VertexConsumer setUv1(int u,int w) {v[9]=u;v[10]=w;return this;}
        public VertexConsumer setUv2(int u,int w) {v[11]=u;v[12]=w;return this;}
        public VertexConsumer setNormal(float x,float y,float z) {v[13]=x;v[14]=y;v[15]=z;return this;}
    }
}
