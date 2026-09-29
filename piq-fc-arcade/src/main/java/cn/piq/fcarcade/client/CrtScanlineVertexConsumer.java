package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Receives the screen's bottom/bottom/top/top quad and replaces it, not overlays it. */
final class CrtScanlineVertexConsumer implements VertexConsumer {
    private final VertexConsumer target;
    private final Matrix4f projection;
    private final int viewportWidth;
    private final int viewportHeight;
    private final double pictureAspect;
    private final boolean scanlines;
    // Position, RGBA, UV, overlay UV, light UV, normal. Positions are already pose-transformed.
    private final float[][] vertices = new float[4][16];
    private int cursor = -1;

    CrtScanlineVertexConsumer(VertexConsumer target, Matrix4f projection, int width, int height) {
        this(target, projection, width, height, 0, true);
    }

    CrtScanlineVertexConsumer(VertexConsumer target, Matrix4f projection, int width, int height,
                             double pictureAspect, boolean scanlines) {
        this.target = target;
        this.projection = projection;
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.pictureAspect = pictureAspect;
        this.scanlines = scanlines;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (++cursor >= 4) throw new IllegalStateException("Incomplete CRT screen quad");
        float[] p = vertices[cursor];
        p[0] = x; p[1] = y; p[2] = z;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        float[] p = vertices[cursor];
        p[3] = r; p[4] = g; p[5] = b; p[6] = a;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        vertices[cursor][7] = u; vertices[cursor][8] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        vertices[cursor][9] = u; vertices[cursor][10] = v;
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        vertices[cursor][11] = u; vertices[cursor][12] = v;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        vertices[cursor][13] = x; vertices[cursor][14] = y; vertices[cursor][15] = z;
        // ArcadeBlockScreenRenderer finishes each vertex with its normal.
        if (cursor == 3) { flush(); cursor = -1; }
        return this;
    }

    private double projectedHeight() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return 0;
        Vector4f[] projected = new Vector4f[4];
        for (int i = 0; i < 4; i++) {
            float[] p = vertices[i];
            Vector4f clip = projection.transform(new Vector4f(p[0], p[1], p[2], 1));
            if (!clip.isFinite() || clip.w <= 0.0001F) return 0;
            projected[i] = clip.div(clip.w);
        }
        // Actual projected edges account for FOV, camera tilt and perspective.
        // The shorter side is conservative for an obliquely viewed screen.
        return Math.min(edgePixels(projected[0], projected[3]),
                edgePixels(projected[1], projected[2]));
    }

    private double edgePixels(Vector4f a, Vector4f b) {
        return Math.hypot((a.x - b.x) * viewportWidth / 2.0,
                (a.y - b.y) * viewportHeight / 2.0);
    }

    private void flush() {
        fitPicture();
        double height = projectedHeight();
        if (!scanlines || CrtScanlinePattern.strength(height) == 0) {
            for (int i = 0; i < 4; i++) emit(i, i, 0, 255);
            return;
        }
        for (int row = 0; row < CrtScanlinePattern.SOURCE_ROWS; row++) {
            float lower = 1 - CrtScanlinePattern.bottom(row);
            float upper = 1 - CrtScanlinePattern.top(row);
            int brightness = CrtScanlinePattern.brightness(row, height);
            emit(0, 3, lower, brightness);
            emit(1, 2, lower, brightness);
            emit(1, 2, upper, brightness);
            emit(0, 3, upper, brightness);
        }
    }

    private void fitPicture() {
        if (!Double.isFinite(pictureAspect) || pictureAspect <= 0) return;
        double width = (distance(0,1)+distance(3,2))*0.5;
        double height = (distance(0,3)+distance(1,2))*0.5;
        if (width <= 0 || height <= 0) return;
        double available = width / height;
        if (pictureAspect < available) {
            float inset = (float)((1-pictureAspect/available)*0.5);
            shrinkEdge(0,1,inset); shrinkEdge(3,2,inset);
        } else {
            float inset = (float)((1-available/pictureAspect)*0.5);
            shrinkEdge(0,3,inset); shrinkEdge(1,2,inset);
        }
    }

    private double distance(int a, int b) {
        double x=vertices[a][0]-vertices[b][0], y=vertices[a][1]-vertices[b][1], z=vertices[a][2]-vertices[b][2];
        return Math.sqrt(x*x+y*y+z*z);
    }

    private void shrinkEdge(int a, int b, float inset) {
        for (int i=0;i<3;i++) {
            float start=vertices[a][i], end=vertices[b][i];
            vertices[a][i]=start+(end-start)*inset;
            vertices[b][i]=end+(start-end)*inset;
        }
    }

    private float mix(int a, int b, int component, float t) {
        return vertices[a][component] + (vertices[b][component] - vertices[a][component]) * t;
    }

    private void emit(int a, int b, float t, int brightness) {
        float shade = brightness / 255F;
        target.addVertex(mix(a, b, 0, t), mix(a, b, 1, t), mix(a, b, 2, t))
                .setColor(Math.round(mix(a, b, 3, t) * shade),
                        Math.round(mix(a, b, 4, t) * shade),
                        Math.round(mix(a, b, 5, t) * shade), Math.round(mix(a, b, 6, t)))
                .setUv(mix(a, b, 7, t), mix(a, b, 8, t))
                .setUv1(Math.round(mix(a, b, 9, t)), Math.round(mix(a, b, 10, t)))
                .setUv2(Math.round(mix(a, b, 11, t)), Math.round(mix(a, b, 12, t)))
                .setNormal(mix(a, b, 13, t), mix(a, b, 14, t), mix(a, b, 15, t));
    }
}
