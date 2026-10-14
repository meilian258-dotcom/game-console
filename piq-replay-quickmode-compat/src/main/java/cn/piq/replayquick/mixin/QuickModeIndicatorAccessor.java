package cn.piq.replayquick.mixin;

import com.replaymod.extras.QuickMode;
import com.replaymod.replay.gui.overlay.GuiReplayOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Use Replay's own indicator update when a safety fallback leaves quick mode. */
@Mixin(value = QuickMode.class, remap = false)
public interface QuickModeIndicatorAccessor {
    @Invoker("updateIndicator")
    void piq$updateIndicator(GuiReplayOverlay overlay, boolean enabled);
}
