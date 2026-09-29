// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.fcarcade.client.rom.LocalRomPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.Set;

/** Keep the historical screen type, but share the bounded folder/list picker with FC. */
final class NativeArcadeSetupScreen extends LocalRomPickerScreen {
    NativeArcadeSetupScreen() {
        super(Component.literal("方块电玩 · 原生街机选择游戏"),
                LocalRomLibrary.arcadeDirectory(Minecraft.getInstance().gameDirectory.toPath()),
                Set.of(".zip"), Set.of("neogeo.zip", "qsound_hle.zip"),
                "确认后记住此机柜游戏；普通右键开关，Shift右键配置。BIOS同目录；Windows单人，暂不支持CHD。",
                NativeArcadeClient::start, () -> NativeArcadeClient.stop(null));
    }
}
