package cn.piq.replaycompat.capture;

import cn.piq.replaycompat.api.ScreenVertex;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;

/** Transparent tee: the original consumer always receives the unmodified draw. */
final class CapturedVertices implements VertexConsumer {
    private final VertexConsumer delegate;
    private final double cameraX, cameraY, cameraZ;
    private final List<ScreenVertex> vertices = new ArrayList<>();
    private float x, y, z, u, v;
    private int color = -1;
    private boolean pending, invalid, finished;

    CapturedVertices(VertexConsumer delegate, double cameraX, double cameraY, double cameraZ) {
        this.delegate = delegate;
        this.cameraX = cameraX; this.cameraY = cameraY; this.cameraZ = cameraZ;
    }

    private void commit() {
        if (!pending || invalid || finished) return;
        if (vertices.size() >= CaptureBudget.MAX_VERTICES || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(z) || !Float.isFinite(u) || !Float.isFinite(v)) {
            invalid = true;
            vertices.clear();
        } else {
            try { vertices.add(new ScreenVertex(x + cameraX, y + cameraY, z + cameraZ, u, v, color)); }
            catch (IllegalArgumentException invalidVertex) { invalid = true; vertices.clear(); }
        }
        pending = false;
    }

    List<ScreenVertex> finish() {
        commit(); finished = true;
        return invalid || vertices.size() % 4 != 0 ? List.of() : List.copyOf(vertices);
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x, y, z); commit();
        this.x = x; this.y = y; this.z = z; this.u = 0; this.v = 0; this.color = -1; pending = true;
        return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        delegate.setColor(r, g, b, a); color = ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255); return this;
    }
    @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); this.u = u; this.v = v; return this; }
    @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
}
