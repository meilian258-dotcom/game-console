package cn.piq.j2mearcade.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Standalone compatibility probe. The supplied game is never packaged. */
public final class RealGameSmokeMain {
    private RealGameSmokeMain() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            runProbe();
        } catch (Throwable error) {
            exitCode = 1;
            error.printStackTrace(System.err);
        }
        // Old MIDlets may leave vendor-owned non-daemon threads behind even
        // after destroyApp. This executable probe must still terminate.
        System.exit(exitCode);
    }

    private static void runProbe() throws Exception {
        Path gameJar = Path.of(requiredProperty("piq.j2me.testJar"));
        Path output = Path.of(requiredProperty("piq.j2me.outputPng"));
        MicroEmuHeadlessSession.Frame accepted = null;
        int distinctColors = 0;
        Set<Integer> frameHashes = new HashSet<>();

        try (MicroEmuHeadlessSession session = MicroEmuHeadlessSession.start(
                gameJar, 176, 204)) {
            long deadline = System.nanoTime() + 12_000_000_000L;
            while (System.nanoTime() < deadline) {
                MicroEmuHeadlessSession.Frame frame = session.snapshot();
                if (frame != null) {
                    frameHashes.add(Arrays.hashCode(frame.argb()));
                    distinctColors = countDistinctColors(frame.argb(), 64);
                    if (distinctColors >= 8) {
                        accepted = frame;
                        break;
                    }
                }
                Thread.sleep(100L);
            }

            if (accepted == null) {
                throw new IllegalStateException("MIDlet started but did not produce a non-blank frame");
            }

            // Let the splash/loader advance while recording whether the game
            // actually produces changing frames, then exercise centre/fire.
            long settleDeadline = System.nanoTime() + 15_000_000_000L;
            while (System.nanoTime() < settleDeadline) {
                MicroEmuHeadlessSession.Frame frame = session.snapshot();
                if (frame != null) {
                    accepted = frame;
                    frameHashes.add(Arrays.hashCode(frame.argb()));
                }
                Thread.sleep(100L);
            }
            for (MicroEmuHeadlessSession.Key key : new MicroEmuHeadlessSession.Key[] {
                    MicroEmuHeadlessSession.Key.FIRE,
                    MicroEmuHeadlessSession.Key.NUM_5,
                    MicroEmuHeadlessSession.Key.SOFT_LEFT,
                    MicroEmuHeadlessSession.Key.SOFT_RIGHT}) {
                session.tap(key);
                Thread.sleep(1_000L);
                MicroEmuHeadlessSession.Frame afterInput = session.snapshot();
                if (afterInput != null) {
                    accepted = afterInput;
                    frameHashes.add(Arrays.hashCode(afterInput.argb()));
                    distinctColors = countDistinctColors(accepted.argb(), 64);
                }
                System.out.printf("J2ME_INPUT_SENT key=%s uniqueFrames=%d%n",
                        key, frameHashes.size());
            }
            if (frameHashes.size() < 3) {
                throw new IllegalStateException(
                        "MIDlet framebuffer did not advance (unique frames=" + frameHashes.size() + ")");
            }

            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            BufferedImage image = new BufferedImage(
                    accepted.width(), accepted.height(), BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, accepted.width(), accepted.height(),
                    accepted.argb(), 0, accepted.width());
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("No PNG writer available");
            }
            System.out.printf(
                    "J2ME_REAL_GAME_FRAME_CAPTURED name=%s size=%dx%d colors>=%d uniqueFrames=%d output=%s%n",
                    session.game().name(), accepted.width(), accepted.height(),
                    distinctColors, frameHashes.size(), output.toAbsolutePath().normalize());
        }
    }

    private static int countDistinctColors(int[] pixels, int stopAfter) {
        Set<Integer> colors = new HashSet<>();
        for (int pixel : pixels) {
            colors.add(pixel);
            if (colors.size() >= stopAfter) {
                return colors.size();
            }
        }
        return colors.size();
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + name);
        }
        return value;
    }
}
