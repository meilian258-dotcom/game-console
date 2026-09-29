package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;
import cn.piq.j2mearcade.core.J2meGameDescriptor;
import cn.piq.j2mearcade.core.MicroEmuHeadlessSession;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

final class J2meGameScreen extends Screen {
    private static final int LCD_WIDTH = 176;
    private static final int LCD_HEIGHT = 204;

    private final J2meGameDescriptor game;
    private MicroEmuHeadlessSession session;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private Component error;
    private long lastFrameNanos;
    private boolean resourcesClosed;

    J2meGameScreen(J2meGameDescriptor game) {
        super(Component.literal(game.name()));
        this.game = game;
    }

    @Override
    protected void init() {
        if (session != null || error != null) {
            return;
        }
        try {
            session = MicroEmuHeadlessSession.start(game.jar(), LCD_WIDTH, LCD_HEIGHT);
            texture = new DynamicTexture(LCD_WIDTH, LCD_HEIGHT, false);
            texture.setFilter(false, false);
            textureId = minecraft.getTextureManager().register("piq_j2me_lcd", texture);
            uploadFrame();
        } catch (IOException | RuntimeException startError) {
            PiqJ2meArcadeMod.LOGGER.error("[PIQ J2ME] Failed to start {}", game.name(), startError);
            error = Component.translatable("screen.piq_j2me_arcade.load_failed",
                    ClientJ2meArcade.safeMessage(startError));
        }
    }

    @Override
    public void tick() {
        // LCDUI games usually target 10-30 FPS. Capturing at 30 FPS keeps the
        // Minecraft render thread responsive while preserving their animation.
        long now = System.nanoTime();
        if (session != null && now - lastFrameNanos >= 33_000_000L) {
            uploadFrame();
            lastFrameNanos = now;
        }
    }

    private void uploadFrame() {
        MicroEmuHeadlessSession.Frame frame = session.snapshot();
        if (frame == null || texture == null) {
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            throw new IllegalStateException("J2ME LCD texture is unavailable");
        }
        int[] pixels = frame.argb();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                image.setPixelRGBA(x, y, argbToAbgr(pixels[y * frame.width() + x]));
            }
        }
        texture.upload();
    }

    private static int argbToAbgr(int color) {
        return color & 0xFF00FF00
                | color >> 16 & 0x000000FF
                | color << 16 & 0x00FF0000;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This screen supplies its own flat backdrop. renderBackground is overridden
        // below because Screen.render calls it again before rendering child widgets.
        graphics.fill(0, 0, width, height, 0xB0101218);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        if (textureId != null) {
            int availableWidth = Math.max(1, width - 40);
            int availableHeight = Math.max(1, height - 78);
            int scale = Math.max(1, Math.min(availableWidth / LCD_WIDTH,
                    availableHeight / LCD_HEIGHT));
            int screenWidth = LCD_WIDTH * scale;
            int screenHeight = LCD_HEIGHT * scale;
            int left = (width - screenWidth) / 2;
            int top = 28 + Math.max(0, (availableHeight - screenHeight) / 2);
            graphics.fill(left - 4, top - 4, left + screenWidth + 4,
                    top + screenHeight + 4, 0xFF101218);
            texture.setFilter(false, false);
            graphics.blit(textureId, left, top, screenWidth, screenHeight,
                    0.0F, 0.0F, LCD_WIDTH, LCD_HEIGHT, LCD_WIDTH, LCD_HEIGHT);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.controls.primary"),
                    width / 2, height - 30, 0xFFFFFFFF);
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.controls.secondary"),
                    width / 2, height - 17, 0xFFB8C0CC);
        } else if (error != null) {
            graphics.drawCenteredString(font, error, width / 2, height / 2, 0xFFFF5555);
        } else {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.piq_j2me_arcade.starting"),
                    width / 2, height / 2, 0xFFFFFFFF);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        // Intentionally empty. The vanilla implementation runs the full-screen GUI
        // blur after our LCD and labels have already been drawn, softening everything.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        MicroEmuHeadlessSession.Key key = J2meKeyMap.map(keyCode);
        if (key != null && session != null) {
            session.keyPressed(key);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        MicroEmuHeadlessSession.Key key = J2meKeyMap.map(keyCode);
        if (key != null && session != null) {
            session.keyReleased(key);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        closeResources();
    }

    private void closeResources() {
        if (resourcesClosed) {
            return;
        }
        resourcesClosed = true;
        if (session != null) {
            session.close();
            session = null;
        }
        if (textureId != null && minecraft != null) {
            minecraft.getTextureManager().release(textureId);
            textureId = null;
            texture = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
