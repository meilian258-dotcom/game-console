package cn.piq.flashbox.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/** Explicit screenless Flash keyboard control; never installs an NES/SFC input profile. */
@EventBusSubscriber(modid="piq_flash_box", value=Dist.CLIENT)
public final class FlashWorldInput {
    private static final FlashWorldInputState STATE = new FlashWorldInputState();
    private static FlashWorldInputState.Masks sent = new FlashWorldInputState.Masks(0, 0);
    private FlashWorldInput() {}

    static boolean active() { return STATE.active(); }

    static boolean enter() {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof FlashBoxScreen) || !FlashBoxClient.canControlTelevision()
                || !mc.isWindowActive() || mc.isPaused() || !FlashBoxClient.acquireInput()) return false;
        FlashBoxClient.pause(false);
        FlashBoxClient.releaseRuntimeInput();
        sent = new FlashWorldInputState.Masks(0, 0);
        STATE.begin(keysDown());
        clearWorld();
        // removed() sees active capture and retains the same input owner and runtime.
        mc.setScreen(null);
        if (mc.screen != null) {
            stop(false);
            if (!(mc.screen instanceof FlashBoxScreen)) FlashBoxClient.releaseInput();
            return false;
        }
        return true;
    }

    static void preview() {
        if (!STATE.active()) return;
        var mc = Minecraft.getInstance();
        // Stop capture before Opening fires; keep OWNER for the replacement local controls.
        stop(false);
        if (!FlashBoxClient.valid()) { FlashBoxClient.releaseInput(); return; }
        var preview = new FlashBoxScreen();
        mc.setScreen(preview);
        if (mc.screen != preview) FlashBoxClient.releaseInput();
    }

    static void stop() { stop(true); }

    private static void stop(boolean releaseOwner) {
        if (!STATE.active()) return;
        STATE.end(keysDown(), buttonsDown(), !Minecraft.getInstance().isWindowActive());
        sent = new FlashWorldInputState.Masks(0, 0);
        FlashBoxClient.releaseRuntimeInput();
        clearWorld();
        if (releaseOwner) FlashBoxClient.releaseInput();
    }

    private static boolean allowed() {
        var mc = Minecraft.getInstance();
        return mc.screen == null && mc.isWindowActive() && !mc.isPaused()
                && FlashBoxClient.ownsInput() && FlashBoxClient.canControlTelevision();
    }

    /** Called before each rendered frame as well as before world movement processing. */
    static void maintain() {
        if (STATE.active() && !allowed()) stop();
        if (!STATE.active() && !STATE.draining()) return;
        var mc = Minecraft.getInstance();
        if (mc.isWindowActive()) STATE.sample(keysDown(), buttonsDown());
        if (STATE.active()) { clearWorld(); sendChanged(); }
    }

    public static boolean intercept(long window, int key, int scan, int action, int modifiers) {
        var mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow()) return false;
        if (STATE.active() && !allowed()) stop();
        if (!STATE.key(key, action, mc.isWindowActive())) return false;
        clearKey(key, scan);
        if (STATE.active() && key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            stop();
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("已退出 Flash 操作；电视继续本机播放。"), true);
        } else if (STATE.active()) {
            sendChanged();
        }
        return true;
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) { maintain(); }

    @SubscribeEvent(priority=EventPriority.HIGHEST, receiveCanceled=true)
    public static void mouse(InputEvent.MouseButton.Pre event) {
        if (STATE.active() && !allowed()) stop();
        boolean back = STATE.active() && STATE.armed() && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
                && event.getAction() == GLFW.GLFW_PRESS;
        if (!STATE.mouse(event.getButton(), event.getAction(), Minecraft.getInstance().isWindowActive())) return;
        event.setCanceled(true);
        clearMouse(event.getButton());
        if (back) preview();
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        if (STATE.active()) event.setCanceled(true);
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void interact(InputEvent.InteractionKeyMappingTriggered event) {
        if (STATE.active()) { event.setSwingHand(false); event.setCanceled(true); }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void movement(MovementInputUpdateEvent event) {
        if (STATE.active()) zero(event.getInput());
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void opening(ScreenEvent.Opening event) {
        if (STATE.active() && event.getNewScreen() != null) stop();
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if ((!STATE.active() && !STATE.draining()) || mc.player == null || mc.screen != null) return;
        String[] lines = STATE.active() ? new String[]{
                "Flash 电视操作 · P1 方向键/空格 · P2 WASD/左Shift",
                "Esc 退出接管 · 右键返回面板 · 鼠标转视角",
                STATE.armed() ? "画面接收约 " + FlashBoxClient.captureFps() + " FPS（非 MC 帧率）" : "请先松开所有按键和鼠标"
        } : new String[]{
                "Flash 已退出接管：旧按键仍在松键保护中",
                "请松开键和鼠标；切回窗口后若仍未恢复，",
                "把原来按住的键/鼠标再按下并松开一次。"
        };
        var graphics = event.getGuiGraphics();
        int width = 0;
        for (var line : lines) width = Math.max(width, mc.font.width(line));
        width = Math.min(width, Math.max(20, graphics.guiWidth() - 24));
        graphics.fill(7, 7, width + 17, 45, 0xbb101820);
        for (int i = 0; i < lines.length; i++) graphics.drawString(mc.font,
                mc.font.plainSubstrByWidth(lines[i], width), 12, 12 + i * 11, 0xffe9dfbf, false);
    }

    private static void sendChanged() {
        var masks = STATE.masks();
        if (!masks.equals(sent)) {
            FlashBoxClient.keys(masks.p1(), masks.p2());
            sent = masks;
        }
    }

    private static Set<Integer> keysDown() {
        var keys = new HashSet<Integer>();
        long window = Minecraft.getInstance().getWindow().getWindow();
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++)
            if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS) keys.add(key);
        return keys;
    }

    private static Set<Integer> buttonsDown() {
        var buttons = new HashSet<Integer>();
        long window = Minecraft.getInstance().getWindow().getWindow();
        for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++)
            if (GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS) buttons.add(button);
        return buttons;
    }

    private static void clearKey(int key, int scan) {
        for (var mapping : Minecraft.getInstance().options.keyMappings)
            if (mapping.matches(key, scan)) drain(mapping);
    }

    private static void clearMouse(int button) {
        for (var mapping : Minecraft.getInstance().options.keyMappings)
            if (mapping.matchesMouse(button)) drain(mapping);
    }

    private static void drain(KeyMapping mapping) {
        mapping.setDown(false);
        while (mapping.consumeClick()) { /* Discard already queued vanilla/mod clicks. */ }
    }

    private static void clearWorld() {
        var mc = Minecraft.getInstance();
        KeyMapping.releaseAll();
        if (mc.player != null) {
            zero(mc.player.input);
            mc.player.setSprinting(false);
        }
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    private static void zero(Input input) {
        input.leftImpulse = input.forwardImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
    }
}
