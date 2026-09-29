package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/** Optional runtime-only exemption; never replace an unreadable private runtime blacklist. */
public final class CartridgeScreenCompat {
    private static boolean warned;
    private CartridgeScreenCompat() {}
    public static void prepare() {
        try {
            Class<?> config = Class.forName("icyllis.modernui.mc.Config");
            Object client = config.getField("CLIENT").get(null);
            Object setting = client.getClass().getField("mBlurBlacklist").get(client);
            Object configured = setting instanceof Supplier<?> supplier ? supplier.get() : null;
            if (!(configured instanceof Collection<?> configuredEntries))
                throw new IllegalStateException("ModernUI configured blacklist is not readable");
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            Class<?> handlerClass = Class.forName("icyllis.modernui.mc.BlurHandler");
            Object handler = handlerClass.getField("INSTANCE").get(null);
            List<String> runtime = FcMenuState.publicBlacklist(handler);
            if (runtime == null) {
                if (!warned) {
                    warned = true;
                    FcArcadeMod.LOGGER.warn("[PIQ FC] ModernUI无完整公开运行时黑名单；保持其他模组名单不变，FC菜单使用无模糊自绘面板");
                }
                return;
            }
            FcMenuState.addNames(entries, configuredEntries);
            FcMenuState.addNames(entries, runtime);
            entries.add(ClientCartridgeEditor.class.getName());
            entries.add(RomLibraryScreen.class.getName());
            entries.add(RomRenameScreen.class.getName());
            entries.add(FcRomDeleteScreen.class.getName());
            entries.add(cn.piq.fcarcade.client.ui.DeviceConfirmScreen.class.getName());
            entries.add(ArcadeSettingsScreen.class.getName());
            entries.add(LeaderboardPanelScreen.class.getName());
            entries.add(ArcadeSaveSlotsScreen.class.getName());
            entries.add(ArcadeSaveCatalogScreen.class.getName());
            entries.add(SkinLibraryScreen.class.getName());
            entries.add("cn.piq.fcarcade.client.rom.LocalRomPickerScreen");
            entries.add("cn.piq.fcarcade.client.cabinet.CabinetSetupScreen");
            entries.add("cn.piq.fcarcade.client.cabinet.CabinetMenuScreen");
            entries.add("cn.piq.sfchome.client.SfcCardEditorScreen");
            entries.add("cn.piq.sfchome.client.SfcJoinScreen");
            entries.add("cn.piq.nativearcade.client.NativeArcadeSetupScreen");
            handlerClass.getMethod("loadBlacklist", List.class).invoke(handler, List.copyOf(entries));
        } catch (ClassNotFoundException ignored) {
            // Optional mod absent.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (!warned) { warned = true; FcArcadeMod.LOGGER.warn("[PIQ FC] FC菜单 Modern UI 兼容初始化失败", error); }
        }
    }
}
