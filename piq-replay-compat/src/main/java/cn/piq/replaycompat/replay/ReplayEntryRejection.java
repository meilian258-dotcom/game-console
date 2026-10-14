package cn.piq.replaycompat.replay;

import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.gui.screen.GuiReplayViewer;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;

/** Adapts an expected admission refusal to ReForgedPlay's checked GUI failure contract. */
public final class ReplayEntryRejection {
    private ReplayEntryRejection() {}

    public static IOException reject(ReplayModReplay mod, IllegalStateException rejection) {
        String reason = rejection.getMessage();
        if (reason == null || reason.isBlank()) reason = "当前游戏尚未安全结束，请先正常结束游戏并确认保存。";
        IOException failure = new IOException("暂时无法打开录像：" + reason, rejection);
        try {
            // The upstream load action leaves its private loading flag set on IOException.
            // Recreate the viewer instead of returning to that disabled instance.
            Minecraft.getInstance().setScreen(new AlertScreen(
                    () -> new GuiReplayViewer(mod).display(),
                    Component.literal("暂时无法打开录像"),
                    Component.literal(reason + "\n\n处理完成后，可返回录像列表重新尝试。"),
                    Component.literal("返回录像列表"), false));
        } catch (RuntimeException | LinkageError displayFailure) {
            // Preserve the checked abort contract even if the client UI itself is unavailable.
            failure.addSuppressed(displayFailure);
        }
        return failure;
    }
}
