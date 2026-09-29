package cn.piq.j2mearcade.client;

import cn.piq.j2mearcade.PiqJ2meArcadeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Optional compatibility bridge for Modern UI.
 *
 * <p>Modern UI can apply its Gaussian blur after an entire vanilla {@code Screen}
 * has been rendered. In that case the J2ME LCD, Minecraft labels and world are all
 * blurred together, so avoiding {@code Screen.renderBackground} is not sufficient.
 * Modern UI exposes a runtime blur blacklist; reflection keeps it an optional
 * dependency and preserves the user's existing blacklist entries.</p>
 */
final class ModernUiBlurCompat {
    private static final String CONFIG_CLASS = "icyllis.modernui.mc.Config";
    private static final String BLUR_HANDLER_CLASS = "icyllis.modernui.mc.BlurHandler";

    private ModernUiBlurCompat() {
    }

    static void prepareScreens() {
        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            Object clientConfig = configClass.getField("CLIENT").get(null);
            Object blacklistItem = clientConfig.getClass()
                    .getField("mBlurBlacklist")
                    .get(clientConfig);
            Object configuredValue = blacklistItem instanceof Supplier<?> supplier
                    ? supplier.get()
                    : null;

            List<String> blacklist = new ArrayList<>();
            if (configuredValue instanceof List<?> configured) {
                for (Object entry : configured) {
                    if (entry instanceof String className && !className.isBlank()) {
                        blacklist.add(className);
                    }
                }
            }
            addIfMissing(blacklist, J2meGameScreen.class.getName());
            addIfMissing(blacklist, J2meLibraryScreen.class.getName());

            Class<?> blurHandlerClass = Class.forName(BLUR_HANDLER_CLASS);
            Object blurHandler = blurHandlerClass.getField("INSTANCE").get(null);
            blurHandlerClass.getMethod("loadBlacklist", List.class)
                    .invoke(blurHandler, blacklist);
            PiqJ2meArcadeMod.LOGGER.debug(
                    "[PIQ J2ME] Registered J2ME screens in Modern UI blur blacklist");
        } catch (ClassNotFoundException ignored) {
            // Modern UI is optional. Vanilla rendering already remains unblurred.
        } catch (ReflectiveOperationException | RuntimeException error) {
            PiqJ2meArcadeMod.LOGGER.warn(
                    "[PIQ J2ME] Could not configure Modern UI blur compatibility", error);
        }
    }

    private static void addIfMissing(List<String> blacklist, String className) {
        if (!blacklist.contains(className)) {
            blacklist.add(className);
        }
    }
}
