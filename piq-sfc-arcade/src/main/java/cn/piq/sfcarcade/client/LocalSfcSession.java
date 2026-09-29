// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.SfcArcadeMod;
import cn.piq.sfcarcade.audio.SfcAudioPlayer;
import cn.piq.sfcarcade.core.SfcControllerState;
import cn.piq.sfcarcade.core.SfcFrameResult;
import cn.piq.sfcarcade.core.SfcRomImage;
import cn.piq.sfcarcade.core.SfcVideoMode;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class LocalSfcSession implements AutoCloseable {
    private final BlockPos blockPos;
    private final ResourceKey<Level> dimension;
    private final Path romPath;
    private final AtomicInteger inputMask = new AtomicInteger();
    private final AtomicReference<Frame> pendingFrame = new AtomicReference<>();
    private final SfcAudioPlayer audioPlayer = new SfcAudioPlayer();
    private final Thread worker;

    private volatile boolean running = true;
    private volatile String error;
    private volatile float audioGain = 0.65F;
    private DynamicTexture texture;
    private ResourceLocation textureLocation;
    private int textureWidth;
    private int textureHeight;
    private float displayAspect = 4.0F / 3.0F;

    LocalSfcSession(BlockPos blockPos, ResourceKey<Level> dimension, Path romPath) {
        this.blockPos = blockPos.immutable();
        this.dimension = dimension;
        this.romPath = romPath;
        this.worker = Thread.ofPlatform()
                .daemon(true)
                .name("PIQ-SFC-" + blockPos.asLong())
                .start(this::runEmulator);
    }

    BlockPos blockPos() {
        return blockPos;
    }

    Path romPath() {
        return romPath;
    }

    boolean matchesLevel(Level level) {
        return level.dimension().equals(dimension);
    }

    ResourceLocation textureLocation() {
        return textureLocation;
    }

    float displayAspect() {
        return displayAspect;
    }

    String error() {
        return error;
    }

    void setInputMask(int mask) {
        inputMask.set(mask);
    }

    void setAudioGain(float gain) {
        audioGain = Math.max(0.0F, Math.min(1.0F, gain));
    }

    void uploadPendingFrame(Minecraft minecraft) {
        Frame frame = pendingFrame.getAndSet(null);
        if (frame == null) return;
        if (texture == null || textureWidth != frame.width || textureHeight != frame.height) {
            releaseTexture(minecraft);
            textureWidth = frame.width;
            textureHeight = frame.height;
            texture = new DynamicTexture(textureWidth, textureHeight, false);
            texture.setFilter(false, false);
            textureLocation = ResourceLocation.fromNamespaceAndPath(
                    SfcArcadeMod.MOD_ID,
                    "frame/" + blockPos.getX() + "_" + blockPos.getY() + "_" + blockPos.getZ());
            minecraft.getTextureManager().register(textureLocation, texture);
        }
        NativeImage image = texture.getPixels();
        if (image == null) throw new IllegalStateException("SFC 方块屏幕纹理缓冲区不可用");
        var pixels = MemoryUtil.memByteBuffer(image.pixels, frame.rgba.length);
        pixels.position(0);
        pixels.put(frame.rgba);
        texture.upload();
        displayAspect = frame.aspect;
    }

    private void runEmulator() {
        try (WasmSfcCore core = new WasmSfcCore()) {
            byte[] romBytes = Files.readAllBytes(romPath);
            core.loadRom(SfcRomImage.fromBytes(romBytes));
            byte[] coreFrame = new byte[0];
            short[] coreAudio = new short[0];
            long nextFrameAt = System.nanoTime();
            while (running) {
                SfcFrameResult result = core.runFrame(
                        new SfcControllerState(inputMask.get()),
                        SfcControllerState.NONE);
                SfcVideoMode mode = result.videoMode();
                int requiredAudioShorts = result.requiredPcmShorts();
                if (coreAudio.length < requiredAudioShorts) {
                    coreAudio = new short[requiredAudioShorts];
                }
                int copiedAudioFrames = core.copyAudioPcm16(coreAudio);
                audioPlayer.submit(coreAudio, copiedAudioFrames, audioGain);

                if (pendingFrame.get() == null) {
                    if (coreFrame.length != mode.requiredRgbaBytes()) {
                        coreFrame = new byte[mode.requiredRgbaBytes()];
                    }
                    core.copyRgbaFrame(coreFrame);
                    byte[] packed = packRows(coreFrame, mode);
                    float aspect = (float) (mode.width() * mode.pixelAspectRatio() / mode.height());
                    pendingFrame.compareAndSet(null, new Frame(
                            mode.width(), mode.height(), aspect, packed));
                }

                long frameNanos = Math.max(1L,
                        Math.round(1_000_000_000.0 / mode.targetFramesPerSecond()));
                nextFrameAt += frameNanos;
                long remaining = nextFrameAt - System.nanoTime();
                if (remaining > 0L) {
                    LockSupport.parkNanos(remaining);
                } else if (remaining < -frameNanos * 4L) {
                    nextFrameAt = System.nanoTime();
                }
            }
        } catch (Throwable throwable) {
            if (running) {
                error = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
                SfcArcadeMod.LOGGER.error("SFC 本地会话启动失败: {}", romPath, throwable);
            }
        }
    }

    private static byte[] packRows(byte[] source, SfcVideoMode mode) {
        int packedStride = mode.width() * SfcVideoMode.BYTES_PER_PIXEL;
        if (mode.rowStrideBytes() == packedStride) {
            return Arrays.copyOf(source, packedStride * mode.height());
        }
        byte[] packed = new byte[packedStride * mode.height()];
        for (int row = 0; row < mode.height(); row++) {
            System.arraycopy(source, row * mode.rowStrideBytes(),
                    packed, row * packedStride, packedStride);
        }
        return packed;
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        audioPlayer.close();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            releaseTexture(minecraft);
        } else {
            minecraft.execute(() -> releaseTexture(minecraft));
        }
    }

    private void releaseTexture(Minecraft minecraft) {
        if (textureLocation != null) {
            minecraft.getTextureManager().release(textureLocation);
        } else if (texture != null) {
            try {
                texture.close();
            } catch (Exception ignored) {
            }
        }
        texture = null;
        textureLocation = null;
        textureWidth = 0;
        textureHeight = 0;
    }

    private record Frame(int width, int height, float aspect, byte[] rgba) {
    }
}
