package cn.piq.j2mearcade.core;

import org.microemu.DisplayComponent;
import org.microemu.EmulatorContext;
import org.microemu.MIDletBridge;
import org.microemu.app.Common;
import org.microemu.app.classloader.MIDletClassLoader;
import org.microemu.app.ui.noui.NoUiDisplayComponent;
import org.microemu.app.util.DeviceEntry;
import org.microemu.device.DeviceDisplay;
import org.microemu.device.FontManager;
import org.microemu.device.InputMethod;
import org.microemu.device.MutableImage;
import org.microemu.device.j2se.J2SEDevice;
import org.microemu.device.j2se.J2SEDeviceDisplay;
import org.microemu.device.j2se.J2SEFontManager;
import org.microemu.device.j2se.J2SEInputMethod;
import org.microemu.device.j2se.J2SEButton;
import org.microemu.device.impl.ButtonName;

import javax.microedition.midlet.MIDlet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Owns one headless MicroEmulator session and exposes its current framebuffer.
 *
 * <p>MicroEmulator keeps the active MIDlet in global static state, so the MVP
 * deliberately allows one session per client process. The game classes are
 * isolated from Minecraft's application class path, but multiple independent
 * machines will still need worker processes.</p>
 */
public final class MicroEmuHeadlessSession implements AutoCloseable {
    private static final Object SESSION_LOCK = new Object();
    private static MicroEmuHeadlessSession activeSession;

    private final J2meGameDescriptor game;
    private final HeadlessContext context;
    private final Common emulator;
    private final Path temporaryJad;
    private final GameJarClassLoader gameClassLoader;
    private boolean closed;

    private MicroEmuHeadlessSession(J2meGameDescriptor game,
                                    HeadlessContext context,
                                    Common emulator,
                                    Path temporaryJad,
                                    GameJarClassLoader gameClassLoader) {
        this.game = game;
        this.context = context;
        this.emulator = emulator;
        this.temporaryJad = temporaryJad;
        this.gameClassLoader = gameClassLoader;
    }

    public static MicroEmuHeadlessSession start(Path jar, int width, int height) throws IOException {
        return startIsolated(jar, width, height);
    }

    private static MicroEmuHeadlessSession startIsolated(
            Path jar, int width, int height) throws IOException {
        if (width < 64 || width > 1024 || height < 64 || height > 1024) {
            throw new IllegalArgumentException("Unsupported virtual display size: " + width + "x" + height);
        }

        J2meGameDescriptor game = J2meGameLibrary.read(jar);
        synchronized (SESSION_LOCK) {
            if (activeSession != null) {
                throw new IllegalStateException("A Java ME session is already active in this process");
            }

            HeadlessContext context = new HeadlessContext();
            Path temporaryJad = createTemporaryJad(game.jar());
            GameJarClassLoader gameClassLoader;
            try {
                gameClassLoader = new GameJarClassLoader(
                        game.jar(), MicroEmuHeadlessSession.class.getClassLoader());
            } catch (IOException error) {
                deleteTemporaryJad(temporaryJad);
                throw error;
            }
            Common emulator;
            try {
                Class<?> bridgeType = gameClassLoader.loadClass(IsolatedGameCommon.class.getName());
                emulator = (Common) bridgeType.getConstructor(EmulatorContext.class)
                        .newInstance(context);
            } catch (ReflectiveOperationException error) {
                gameClassLoader.close();
                deleteTemporaryJad(temporaryJad);
                throw new IOException("Failed to create isolated MIDlet loader", error);
            }
            MicroEmuHeadlessSession session = new MicroEmuHeadlessSession(
                    game, context, emulator, temporaryJad, gameClassLoader);
            activeSession = session;
            try {
                // MicroEmulator 2.0.4's optional preprocessor depends on ASM 3.
                // Disabling it avoids an unsafe package conflict with NeoForge's
                // modern ASM. Ordinary MIDP applications do not require it.
                MIDletClassLoader.instrumentMIDletClasses = false;

                List<String> arguments = new ArrayList<>(List.of(
                        "--rms", "memory",
                        "--resizableDevice", Integer.toString(width), Integer.toString(height)));
                arguments.add("--usesystemclassloader");
                arguments.add(game.midletEntry());
                arguments.add("--propertiesjad");
                arguments.add(temporaryJad.toString());
                DeviceEntry device = new DeviceEntry(
                        "PIQ headless device",
                        null,
                        "org/microemu/device/default/device.xml",
                        true,
                        false);
                // This legacy API returns an exit-on-destroy flag, not a
                // success result. Headless mode intentionally ignores it.
                emulator.initParams(arguments, device, J2SEDevice.class);
                emulator.initMIDlet(true);
                MIDlet currentMidlet = MIDletBridge.getCurrentMIDlet();
                if (currentMidlet == null
                        || !currentMidlet.getClass().getName().equals(game.midletEntry())) {
                    throw new IOException("MIDlet did not start: " + game.midletEntry());
                }
                return session;
            } catch (Throwable error) {
                session.closeAfterFailedStart();
                if (error instanceof IOException ioError) {
                    throw ioError;
                }
                throw new IOException("Failed to start MIDlet " + game.name(), error);
            }
        }
    }

    public J2meGameDescriptor game() {
        return game;
    }

    public Frame snapshot() {
        synchronized (SESSION_LOCK) {
            ensureOpen();
            // A number of old vendor-specific games update their own state
            // without reliably posting an LCDUI repaint event. Rendering the
            // current Displayable here prevents NoUiDisplayComponent from
            // returning a stale splash/loading frame indefinitely.
            context.deviceDisplay.repaint(
                    0,
                    0,
                    context.deviceDisplay.getFullWidth(),
                    context.deviceDisplay.getFullHeight());
            MutableImage image = context.displayComponent.getDisplayImage();
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            // MicroEmulator 2.0.4's J2SEMutableImage#getData() reuses one
            // PixelGrabber. A PixelGrabber is a one-shot consumer, so that API
            // keeps returning the first captured frame forever. getRGB()
            // creates a fresh grabber and therefore observes live pixels.
            image.getRGB(pixels, 0, width, 0, 0, width, height);
            return new Frame(width, height, pixels);
        }
    }

    public void keyPressed(Key key) {
        synchronized (SESSION_LOCK) {
            ensureOpen();
            context.inputMethod.buttonPressed(findButton(key), '\0');
        }
    }

    public void keyReleased(Key key) {
        synchronized (SESSION_LOCK) {
            ensureOpen();
            context.inputMethod.buttonReleased(findButton(key), '\0');
        }
    }

    public void tap(Key key) throws InterruptedException {
        keyPressed(key);
        Thread.sleep(80L);
        keyReleased(key);
    }

    private J2SEButton findButton(Key key) {
        Vector<?> buttons = emulator.getDevice().getButtons();
        for (Object candidate : buttons) {
            if (candidate instanceof J2SEButton button
                    && button.getFunctionalName() == key.buttonName) {
                return button;
            }
        }
        throw new IllegalArgumentException("Virtual device has no key: " + key);
    }

    @Override
    public void close() {
        synchronized (SESSION_LOCK) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                Common.dispose();
            } finally {
                MIDletBridge.clear();
                context.inputMethod.dispose();
                if (activeSession == this) {
                    activeSession = null;
                }
                deleteTemporaryJad(temporaryJad);
                try {
                    gameClassLoader.close();
                } catch (IOException ignored) {
                    // The session is already stopped; there is nothing useful
                    // callers can do with a late URLClassLoader close failure.
                }
            }
        }
    }

    /**
     * MicroEmulator only imports application properties from a JAD when a
     * MIDlet class name is launched directly. The compatibility probe uses
     * that launch path to avoid the emulator's legacy 16 KiB class limit, so
     * mirror the jar manifest into a short-lived JAD instead of silently
     * dropping properties that old games commonly depend on.
     */
    private static Path createTemporaryJad(Path jar) throws IOException {
        Manifest manifest;
        try (JarFile archive = new JarFile(jar.toFile(), false)) {
            manifest = archive.getManifest();
        }
        if (manifest == null) {
            throw new IOException("Missing META-INF/MANIFEST.MF: " + jar);
        }

        StringBuilder jad = new StringBuilder();
        for (var entry : manifest.getMainAttributes().entrySet()) {
            Attributes.Name name = (Attributes.Name) entry.getKey();
            if (!Attributes.Name.MANIFEST_VERSION.equals(name)) {
                jad.append(name).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        jad.append("MIDlet-Jar-URL: ").append(jar.getFileName()).append("\r\n");
        jad.append("MIDlet-Jar-Size: ").append(Files.size(jar)).append("\r\n");

        Path result = Files.createTempFile("piq-j2me-", ".jad");
        Files.writeString(result, jad, StandardCharsets.UTF_8);
        return result;
    }

    private static void deleteTemporaryJad(Path temporaryJad) {
        if (temporaryJad == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryJad);
        } catch (IOException ignored) {
            temporaryJad.toFile().deleteOnExit();
        }
    }

    private void closeAfterFailedStart() {
        try {
            close();
        } catch (Throwable ignored) {
            MIDletBridge.clear();
            activeSession = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Java ME session is closed");
        }
    }

    public record Frame(int width, int height, int[] argb) {
        public Frame {
            argb = argb.clone();
        }

        @Override
        public int[] argb() {
            return argb.clone();
        }
    }

    public enum Key {
        UP(ButtonName.UP),
        DOWN(ButtonName.DOWN),
        LEFT(ButtonName.LEFT),
        RIGHT(ButtonName.RIGHT),
        FIRE(ButtonName.SELECT),
        SOFT_LEFT(ButtonName.SOFT1),
        SOFT_RIGHT(ButtonName.SOFT2),
        NUM_0(ButtonName.KEY_NUM0),
        NUM_1(ButtonName.KEY_NUM1),
        NUM_2(ButtonName.KEY_NUM2),
        NUM_3(ButtonName.KEY_NUM3),
        NUM_4(ButtonName.KEY_NUM4),
        NUM_5(ButtonName.KEY_NUM5),
        NUM_6(ButtonName.KEY_NUM6),
        NUM_7(ButtonName.KEY_NUM7),
        NUM_8(ButtonName.KEY_NUM8),
        NUM_9(ButtonName.KEY_NUM9),
        STAR(ButtonName.KEY_STAR),
        POUND(ButtonName.KEY_POUND);

        private final ButtonName buttonName;

        Key(ButtonName buttonName) {
            this.buttonName = buttonName;
        }
    }

    private static final class HeadlessContext implements EmulatorContext {
        private final NoUiDisplayComponent displayComponent = new NoUiDisplayComponent();
        private final J2SEInputMethod inputMethod = new J2SEInputMethod();
        private final J2SEDeviceDisplay deviceDisplay = new J2SEDeviceDisplay(this);
        private final J2SEFontManager fontManager = new J2SEFontManager();

        @Override
        public DisplayComponent getDisplayComponent() {
            return displayComponent;
        }

        @Override
        public InputMethod getDeviceInputMethod() {
            return inputMethod;
        }

        @Override
        public DeviceDisplay getDeviceDisplay() {
            return deviceDisplay;
        }

        @Override
        public FontManager getDeviceFontManager() {
            return fontManager;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            MIDlet midlet = MIDletBridge.getCurrentMIDlet();
            if (midlet != null) {
                return midlet.getClass().getResourceAsStream(name);
            }
            return MicroEmuHeadlessSession.class.getResourceAsStream(name);
        }

        @Override
        public boolean platformRequest(String url) {
            // Never allow a game jar to launch an external browser/application.
            return false;
        }
    }
}
