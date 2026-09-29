package cn.piq.retro.client;

import cn.piq.retro.input.GamepadState;
import cn.piq.retro.input.GamepadState.Control;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Client-only standard GLFW gamepad adapter. Never owns networking, a player port or a core. */
public final class GamepadInput {
    public enum ProfileKind { NES, SFC, ARCADE }
    public record Device(String key, String name, int slot, boolean mapped) { }
    private static final GamepadMixer MIXER = new GamepadMixer();
    private static final String[] signatures = new String[GLFW.GLFW_JOYSTICK_LAST + 1];
    private static final long[] generations = new long[GLFW.GLFW_JOYSTICK_LAST + 1];
    private static GamepadConfigStore.Loaded loaded;
    private static String status = "自动识别手柄；仅在操作机器时生效";
    private static long lastErrorLog;
    private GamepadInput() { }

    /** Input/output keep the host's native order: NES8 or canonical SFC/arcade12. */
    public static int mix(Object owner, ProfileKind kind, int nativeMask, boolean active) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread()) { MIXER.pause(owner); return nativeMask; }
            ensureLoaded();
            boolean focused = active && mc.screen == null && mc.isWindowActive() && !mc.isPaused();
            if (!focused || !loaded.config().enabled()) {
                status = loaded.config().enabled() ? "手柄待命；仅在操作机器时生效" : "实体手柄已禁用";
                return MIXER.mix(owner, kind.name(), nativeMask, false, null);
            }
            GamepadState sample = sample(loaded.config().deviceKey());
            int result = MIXER.mix(owner, kind.name(), nativeMask, true, sample);
            status = sample == null ? "所选手柄未连接或没有标准映射；键盘仍可用"
                    : MIXER.armed(owner) ? "手柄已连接" : "请松开手柄按键并将摇杆居中";
            return result;
        } catch (RuntimeException | LinkageError failure) {
            MIXER.pause(owner); report(failure); return nativeMask;
        }
    }

    public static void pause(Object owner) { MIXER.pause(owner); }
    public static void release(Object owner) { MIXER.release(owner); }
    public static Screen settings(Screen parent) { return settings(parent,ProfileKind.valueOf(KeyboardInput.settingsProfile().name()),""); }
    public static Screen settings(Screen parent,ProfileKind initialProfile,String deviceLabel) { return new GamepadSettingsScreen(parent,initialProfile,deviceLabel); }
    public static String status() { return status; }

    static GamepadConfigStore.Loaded settingsSnapshot() {
        ensureLoaded();
        try { return GamepadConfigStore.load(FMLPaths.GAMEDIR.get()); }
        catch (IOException failure) { report(failure); return new GamepadConfigStore.Loaded(loaded.config(), "unreadable", status); }
    }

    static void save(GamepadConfig draft, String revision) throws IOException {
        var saved = GamepadConfigStore.save(FMLPaths.GAMEDIR.get(), draft, revision);
        loaded = saved; MIXER.configure(draft);
        status = draft.enabled() ? "设置已保存；松键并居中后启用" : "实体手柄已禁用";
    }

    private static void ensureLoaded() {
        if (loaded != null) return;
        try { loaded = GamepadConfigStore.load(FMLPaths.GAMEDIR.get()); }
        catch (IOException failure) {
            loaded = new GamepadConfigStore.Loaded(GamepadConfig.defaults().enabled(false), "unreadable", "配置读取失败，实体手柄暂时禁用"); report(failure);
        }
        MIXER.configure(loaded.config());
        if (!loaded.warning().isEmpty()) status = loaded.warning();
    }

    /** Inventory includes unmapped joysticks for an honest UI; only mapped devices can be chosen. */
    static List<Device> devices() {
        List<Device> devices = new ArrayList<>();
        for (int slot = GLFW.GLFW_JOYSTICK_1; slot <= GLFW.GLFW_JOYSTICK_LAST; slot++) {
            if (!GLFW.glfwJoystickPresent(slot)) { signatures[slot] = null; continue; }
            String guid = GLFW.glfwGetJoystickGUID(slot);
            String key = slot + ":" + (guid == null ? "unknown" : guid);
            boolean mapped = GLFW.glfwJoystickIsGamepad(slot);
            String name = mapped ? GLFW.glfwGetGamepadName(slot) : GLFW.glfwGetJoystickName(slot);
            String signature = key + ":" + name + ":" + mapped;
            if (!signature.equals(signatures[slot])) { signatures[slot] = signature; generations[slot]++; }
            devices.add(new Device(key, name == null ? "手柄 " + (slot + 1) : name, slot, mapped));
        }
        return List.copyOf(devices);
    }

    static GamepadState sample(String selectedKey) {
        Device selected = null;
        for (Device device : devices()) if (device.mapped() && (selectedKey.isEmpty() || device.key().equals(selectedKey))) { selected = device; break; }
        if (selected == null) return null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWGamepadState state = GLFWGamepadState.malloc(stack);
            if (!GLFW.glfwGetGamepadState(selected.slot(), state)) { signatures[selected.slot()] = null; return null; }
            byte[] buttons = new byte[15]; float[] axes = new float[6];
            for (int i = 0; i < buttons.length; i++) buttons[i] = state.buttons(i);
            for (int i = 0; i < axes.length; i++) axes[i] = state.axes(i);
            return StandardGamepadState.decode(new GamepadState.DeviceId(selected.key(), generations[selected.slot()]), buttons, axes);
        }
    }

    private static void report(Throwable failure) {
        status = "手柄输入不可用，已保留键盘；请检查设备或设置";
        long now = System.nanoTime();
        if (now - lastErrorLog > 60_000_000_000L || lastErrorLog == 0) {
            lastErrorLog = now; LogUtils.getLogger().warn("PIQ gamepad input/configuration unavailable: {}", failure.toString());
        }
    }
}
