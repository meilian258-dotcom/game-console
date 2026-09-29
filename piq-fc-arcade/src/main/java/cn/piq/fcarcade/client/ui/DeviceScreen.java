package cn.piq.fcarcade.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A self-painted device page: subclasses draw their dim backdrop/panel, then
 * call super.render for widgets. Vanilla Screen.render invokes renderBackground
 * before widgets; repainting or blurring there would cover the existing text.
 * Confirmations use ordinary Screen and the vanilla background-first order.
 */
public abstract class DeviceScreen extends Screen {
    protected DeviceScreen(Component title) { super(title); }

    @Override
    public final void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The subclass has already painted its own background. Never blur its foreground.
    }
}
