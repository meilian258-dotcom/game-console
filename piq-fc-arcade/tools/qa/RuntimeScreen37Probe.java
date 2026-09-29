import cn.piq.fcarcade.client.runtime.RuntimeEnvironmentScreen;
import cn.piq.fcarcade.client.runtime.RuntimePanelLayout;
import cn.piq.fcarcade.runtime.RuntimeCatalog;
import cn.piq.fcarcade.runtime.RuntimeInstaller;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.main.GameConfig;
import net.minecraft.network.chat.Component;
import sun.misc.Unsafe;

/** Actual Screen keyboard events and actual widget geometry, with explicitly controlled MC navigation. */
public final class RuntimeScreen37Probe {
    private static Unsafe unsafe;
    private static int assertions;
    private static void check(boolean ok, String text) { assertions++; if (!ok) throw new AssertionError(text); }
    private static void field(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field field = RuntimeEnvironmentScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RuntimeEnvironmentScreen.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args);
    }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner, RuntimeEnvironmentScreen.class, name, value); }

    /** Its constructor is never called. No Minecraft startup, GLFW, GL, audio or socket. */
    public static final class Navigation extends Minecraft {
        Screen last; int navigations; ClientPacketListener connection; IntegratedServer server;
        private Navigation() { super((GameConfig) null); }
        @Override public void setScreen(Screen screen) { last = screen; this.screen = screen; navigations++; }
        @Override public ClientPacketListener getConnection() { return connection; }
        @Override public IntegratedServer getSingleplayerServer() { return server; }
    }
    private static final class Parent extends Screen { Parent() { super(Component.literal("QA parent")); } }

    private static RuntimeEnvironmentScreen screen(Navigation mc, Screen parent, boolean busy, boolean installing) throws Exception {
        // Inject state only to avoid ModList/Minecraft constructors. Every exercised production
        // onClose/removed/init and inherited Screen.keyPressed/widget constructor remains real.
        var screen = (RuntimeEnvironmentScreen) unsafe.allocateInstance(RuntimeEnvironmentScreen.class);
        set(screen, "parent", parent); set(screen, "required", EnumSet.allOf(RuntimeCatalog.RuntimeId.class));
        set(screen, "busy", busy); set(screen, "installing", installing); set(screen, "checked", true);
        set(screen, "cancelled", new AtomicBoolean());
        field(screen, Screen.class, "minecraft", mc);
        for (String name : List.of("children", "renderables", "narratables")) field(screen, Screen.class, name, new ArrayList<>());
        return screen;
    }

    public static void main(String[] args) throws Exception {
        Path fc = Path.of(args[0]).toRealPath(), mcJar = Path.of(args[1]).toRealPath();
        for (Class<?> type : List.of(RuntimeEnvironmentScreen.class, RuntimePanelLayout.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(fc), "Final FC origin " + type);
        check(Path.of(Screen.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(mcJar), "Actual 1.21.1 Screen origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Field access = Unsafe.class.getDeclaredField("theUnsafe"); access.setAccessible(true); unsafe = (Unsafe) access.get(null);
        var mc = (Navigation) unsafe.allocateInstance(Navigation.class); var parent = new Parent();
        var installing = screen(mc, parent, true, true);
        check(installing.keyPressed(256, 0, 0), "Actual Minecraft ESC dispatch consumed");
        check(((AtomicBoolean) get(installing, "cancelled")).get(), "ESC signals cancellation");
        check((boolean) get(installing, "returnAfterCancel"), "ESC requests return after cleanup");
        check((boolean) get(installing, "busy") && (boolean) get(installing, "installing"), "ESC does not pretend worker has completed");
        check(mc.navigations == 0, "No title navigation while install cleanup pending");
        installing.removed(); check(((AtomicBoolean) get(installing, "cancelled")).get(), "External screen removal cancels worker");
        check(mc.navigations == 0, "Removal does not hijack another screen");
        var checking = screen(mc, parent, true, false);
        check(checking.keyPressed(256, 0, 0), "Actual ESC in read-only inspection");
        check(mc.navigations == 1 && mc.last == parent, "Read-only inspection permits return to exact parent");
        check(((AtomicBoolean) get(checking, "cancelled")).get(), "Inspection cancelled on return");
        var idle = screen(mc, parent, false, false); idle.onClose();
        check(mc.navigations == 2 && mc.last == parent, "Idle return targets exact parent");
        check(!idle.isPauseScreen(), "Settings never pause a world");
        check((boolean) invoke(idle, "outsideWorld", new Class<?>[0]), "Detached client permits installation");
        mc.connection = (ClientPacketListener) unsafe.allocateInstance(ClientPacketListener.class);
        check(!(boolean) invoke(idle, "outsideWorld", new Class<?>[0]), "Connection without level blocks install");
        invoke(idle, "start", new Class<?>[]{boolean.class}, true); check(!(boolean) get(idle, "busy"), "Connection gate rejects before worker launch");
        mc.connection = null; mc.server = (IntegratedServer) unsafe.allocateInstance(IntegratedServer.class);
        check(!(boolean) invoke(idle, "outsideWorld", new Class<?>[0]), "Integrated server teardown blocks install");
        invoke(idle, "start", new Class<?>[]{boolean.class}, true); check(!(boolean) get(idle, "busy"), "Integrated-server gate rejects before worker launch");
        mc.server = null; mc.level = (ClientLevel) unsafe.allocateInstance(ClientLevel.class);
        check(!(boolean) invoke(idle, "outsideWorld", new Class<?>[0]), "Actual world state blocks install");
        invoke(idle, "start", new Class<?>[]{boolean.class}, true); check(!(boolean) get(idle, "busy"), "World gate rejects before worker launch"); mc.level = null;
        for (int[] size : new int[][]{{320,240},{420,280},{640,360},{854,480},{1280,720},{280,180}}) {
            var page = screen(mc, parent, false, false); page.width = size[0]; page.height = size[1];
            invoke(page, "init", new Class<?>[0]);
            var widgets = page.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
            boolean supported = RuntimePanelLayout.of(size[0], size[1]).supported();
            check(widgets.size() == (supported ? 6 : 1), "Actual widget count at " + size[0] + "x" + size[1]);
            for (var widget : widgets) {
                check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= size[0]
                        && widget.getY() + widget.getHeight() <= size[1], "Actual widget bounds fit");
                check(widget.getHeight() == 20, "Native GUI widget height without scaling");
            }
            if (supported) {
                check(!widgets.get(1).active, "No report cannot enable install");
                ((Button) widgets.get(5)).onPress(); check(mc.last == parent, "Actual return button event navigates parent");
            }
        }
        System.out.println("{\"ok\":true,\"assertions\":" + assertions + ",\"actual_minecraft_screen_keyboard\":true,\"actual_widget_bounds\":true,\"controlled_navigation_only\":true,\"minecraft_started\":false,\"gl_window_created\":false,\"installer_started\":false}");
    }
}
