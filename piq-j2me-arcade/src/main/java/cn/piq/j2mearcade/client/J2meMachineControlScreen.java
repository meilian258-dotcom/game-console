package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.core.MicroEmuHeadlessSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class J2meMachineControlScreen extends Screen {
    private final ClientJ2meMachineRuntime runtime;

    J2meMachineControlScreen(ClientJ2meMachineRuntime runtime) {
        super(Component.literal(runtime.gameName()));
        this.runtime = runtime;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, 22, 0xA0000000);
        graphics.fill(0, height - 35, width, height, 0xA0000000);
        graphics.drawCenteredString(font,
                Component.translatable("screen.piq_j2me_arcade.machine_control", title),
                width / 2, 7, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("screen.piq_j2me_arcade.controls.primary"),
                width / 2, height - 29, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("screen.piq_j2me_arcade.controls.secondary"),
                width / 2, height - 16, 0xFFB8C0CC);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The world remains sharp and the LCD is rendered directly on the machine.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        MicroEmuHeadlessSession.Key key = J2meKeyMap.map(keyCode);
        if (key != null) {
            runtime.keyPressed(key);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        MicroEmuHeadlessSession.Key key = J2meKeyMap.map(keyCode);
        if (key != null) {
            runtime.keyReleased(key);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
