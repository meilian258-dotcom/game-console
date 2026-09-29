package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.core.J2meGameDescriptor;
import cn.piq.j2mearcade.core.MicroEmuHeadlessSession;
import cn.piq.j2mearcade.world.J2meArcadeBlock;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

final class ClientJ2meMachineRuntime {
    static final ClientJ2meMachineRuntime INSTANCE = new ClientJ2meMachineRuntime();
    static final int LCD_WIDTH = 176;
    static final int LCD_HEIGHT = 204;

    private MicroEmuHeadlessSession session;
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private BlockPos blockPos;
    private String fileName = "";
    private String gameName = "";
    private long lastFrameNanos;

    private ClientJ2meMachineRuntime() {
    }

    void start(BlockPos pos, J2meGameDescriptor game) throws IOException {
        String requestedFile = game.jar().getFileName().toString();
        if (session != null && pos.equals(blockPos) && requestedFile.equals(fileName)) {
            return;
        }
        close();
        session = MicroEmuHeadlessSession.start(game.jar(), LCD_WIDTH, LCD_HEIGHT);
        blockPos = pos.immutable();
        fileName = requestedFile;
        gameName = game.name();
        texture = new DynamicTexture(LCD_WIDTH, LCD_HEIGHT, false);
        texture.setFilter(false, false);
        textureId = Minecraft.getInstance().getTextureManager()
                .register("piq_j2me_machine_lcd", texture);
        uploadFrame();
    }

    void update() {
        Minecraft minecraft = Minecraft.getInstance();
        if (session == null) {
            return;
        }
        if (minecraft.level == null || blockPos == null
                || !(minecraft.level.getBlockState(blockPos).getBlock() instanceof J2meArcadeBlock)) {
            close();
            return;
        }
        long now = System.nanoTime();
        if (now - lastFrameNanos >= 33_000_000L) {
            uploadFrame();
            lastFrameNanos = now;
        }
    }

    void keyPressed(MicroEmuHeadlessSession.Key key) {
        if (session != null) {
            session.keyPressed(key);
        }
    }

    void keyReleased(MicroEmuHeadlessSession.Key key) {
        if (session != null) {
            session.keyReleased(key);
        }
    }

    boolean activeAt(BlockPos pos) {
        return session != null && pos != null && pos.equals(blockPos) && textureId != null;
    }

    BlockPos blockPos() {
        return blockPos;
    }

    ResourceLocation textureId() {
        return textureId;
    }

    String gameName() {
        return gameName;
    }

    private void uploadFrame() {
        if (session == null || texture == null) {
            return;
        }
        MicroEmuHeadlessSession.Frame frame = session.snapshot();
        if (frame == null) {
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            throw new IllegalStateException("J2ME LCD texture is unavailable");
        }
        int[] pixels = frame.argb();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                int color = pixels[y * frame.width() + x];
                int abgr = color & 0xFF00FF00
                        | color >> 16 & 0x000000FF
                        | color << 16 & 0x00FF0000;
                image.setPixelRGBA(x, y, abgr);
            }
        }
        texture.upload();
    }

    void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (textureId != null) {
            minecraft.getTextureManager().release(textureId);
        }
        textureId = null;
        texture = null;
        blockPos = null;
        fileName = "";
        gameName = "";
    }
}
