// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.api.replay;

import cn.piq.fcarcade.client.ScreenRenderMaterial;
import java.util.Objects;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Client-only presentation contract v1 for Minecraft 1.21.1.
 * The main mod owns this class exactly once. Optional observers must not bundle it.
 * Notifications are synchronous on the render owner, optional, and never perform IO.
 * A material identifies game pixels; vertices still come from the actual MC/Iris buffer,
 * after device/Sable/CRT transforms, not from reconstructed block geometry.
 */
public final class ReplayPresentation {
    public static final int API_VERSION = 1;
    public interface Observer {
        void material(ResourceLocation texture, RenderType material);
        void anchor(BlockPos position);
        void clearAnchor();
    }
    private static volatile Observer observer;
    private ReplayPresentation() {}
    public static synchronized void install(Observer value) {
        Objects.requireNonNull(value);
        if (observer != null && observer != value) throw new IllegalStateException("Replay presentation observer already installed");
        observer = value;
    }
    public static void material(ResourceLocation texture, RenderType type) {
        var current = observer;
        try { if (current != null) current.material(texture, type); }
        catch (RuntimeException | LinkageError ignored) { /* Optional recording cannot break device drawing. */ }
    }
    public static void anchor(BlockPos position) {
        var current = observer;
        try { if (current != null) current.anchor(position); }
        catch (RuntimeException | LinkageError ignored) { }
    }
    public static void clearAnchor() {
        var current = observer;
        try { if (current != null) current.clearAnchor(); }
        catch (RuntimeException | LinkageError ignored) { }
    }
    /** Stable entry to the opaque, depth-tested, Iris-aware world-screen material. */
    public static RenderType screenMaterial(ResourceLocation texture) {
        return ScreenRenderMaterial.of(Objects.requireNonNull(texture));
    }
}
