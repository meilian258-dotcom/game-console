package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Client-boundary contracts supplement pure tests without booting Minecraft/native GLFW. */
class GamepadClientSourceContractTest {
    @Test void adapterHasMainThreadScreenFocusAndPausedGuardsAndDoesNotTakeGlobalCallbacks() throws Exception {
        String source = source("GamepadInput.java");
        assertTrue(source.contains("mc.isSameThread()"));
        assertTrue(source.contains("active && mc.screen == null && mc.isWindowActive() && !mc.isPaused()"));
        assertTrue(source.contains("catch (RuntimeException | LinkageError failure)"));
        assertTrue(source.contains("MIXER.pause(owner); report(failure); return nativeMask;"));
        assertFalse(source.contains("glfwSetJoystickCallback"));
        assertFalse(source.contains("glfwSetKeyCallback"));
        assertFalse(source.contains("InputOwnership.acquire"));
        assertFalse(source.contains("PacketDistributor"));
        assertFalse(source.contains("sendToServer"));
    }

    @Test void onlyMappedDevicesAndAutomaticOrExplicitlySelectedKeyProduceSamples() throws Exception {
        String source = source("GamepadInput.java");
        assertTrue(source.contains("glfwJoystickIsGamepad(slot)"));
        assertTrue(source.contains("device.mapped() && (selectedKey.isEmpty() || device.key().equals(selectedKey))"));
        assertTrue(source.contains("StandardGamepadState.decode"));
        assertTrue(source.contains("glfwGetGamepadState"));
        assertTrue(source.contains("GLFW.GLFW_JOYSTICK_LAST"));
    }

    @Test void settingsOnlyPersistFromExplicitSaveNotCloseRefreshOrCapture() throws Exception {
        String source = source("GamepadSettingsScreen.java");
        assertEquals(1, occurrences(source, "GamepadInput.save("));
        int save = source.indexOf("private void save()");
        int close = source.indexOf("@Override public void onClose()", save);
        assertTrue(source.substring(save, close).contains("GamepadInput.save(draft, revision)"));
        assertFalse(source.substring(close).contains("GamepadInput.save("));
        assertTrue(source.contains("capturing = null; minecraft.setScreen(parent)"));
        assertTrue(source.contains("if (!minecraft.isWindowActive()) { captureNeutral = false;"));
        assertTrue(source.contains("centered(sample, draft.deadzoneExit())"));
        assertFalse(source.contains("Files.write"));
    }

    @Test void screenDrawsBoundedVanillaPanelBeforeWidgetsAndTextWithoutBlur() throws Exception {
        String source = source("GamepadSettingsScreen.java");
        assertTrue(source.contains("@Override public void renderBackground"));
        assertTrue(source.contains("g.fill(0, 0, width, height"));
        int render = source.indexOf("@Override public void render(GuiGraphics");
        String body = source.substring(render);
        assertTrue(body.indexOf("super.render(") < body.indexOf("g.drawCenteredString("));
        assertFalse(body.contains("renderBlurredBackground"));
        assertTrue(source.contains("DeviceUi.panel("));
        assertTrue(source.contains("ControlPanelLayout.of(width,height)"));
        assertTrue(source.contains("new GamepadDeviceScreen("));
    }

    private static int occurrences(String text, String needle) {
        int count = 0, start = 0;
        while ((start = text.indexOf(needle, start)) >= 0) { count++; start += needle.length(); }
        return count;
    }

    private static String source(String file) throws IOException {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; cursor != null && i < 5; i++, cursor = cursor.getParent()) {
            for (String prefix : new String[]{"", "piq-fc-arcade/"}) {
                Path path = cursor.resolve(prefix + "src/main/java/cn/piq/retro/client/" + file);
                if (Files.isRegularFile(path)) return Files.readString(path);
            }
        }
        throw new IOException("Missing client source " + file);
    }
}
