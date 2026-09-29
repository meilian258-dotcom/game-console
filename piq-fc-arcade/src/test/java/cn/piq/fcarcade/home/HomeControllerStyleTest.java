package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real pure codec; source contracts guard its Minecraft adapter and authority boundary. */
class HomeControllerStyleTest {
    @Test void legacyMissingAndUnrecognizedNamesKeepTheOriginalFcAppearance() {
        for (String value : new String[]{null, "", "famicom", "SUBOR", "other", "../subor"})
            assertEquals(HomeControllerData.Style.FAMICOM, HomeControllerData.Style.fromId(value));
    }

    @Test void bothSupportedStylesRoundTripThroughTheirStableNbtNames() {
        for (var style : HomeControllerData.Style.values())
            assertSame(style, HomeControllerData.Style.fromId(style.serializedName()));
        assertEquals("subor", HomeControllerData.Style.SUBOR.serializedName());
    }

    @Test void grantsChooseOriginAndP2LeaseRotationPreservesAppearance() throws Exception {
        String data = source("HomeControllerData");
        String service = source("HomeControllerService");
        assertTrue(data.contains("bind(stack, lease, style(stack))"));
        assertTrue(data.contains("tag.putString(\"Style\", (style == null ? Style.FAMICOM : style).serializedName())"));
        assertTrue(service.contains("console.getBlockState().getBlock() instanceof SuborConsoleBlock"));
        assertTrue(service.contains("HomeControllerData.bind(stack, owned, style)"));
        assertTrue(service.contains("HomeControllerData.bind(received, next)"));
    }

    @Test void renderStyleCannotEnterLedgerOrInputAuthorization() throws Exception {
        assertFalse(source("HomeControllerLedger").contains("Style"));
        assertFalse(source("HomeControllerInventory").contains("Style"));
        String service = source("HomeControllerService");
        int start = service.indexOf("public static boolean authorized(");
        int end = service.indexOf("public static void activate(", start);
        assertTrue(start >= 0 && end > start);
        String authorization = service.substring(start, end);
        assertFalse(authorization.contains("style"));
        assertTrue(authorization.contains("state.ledger.authorized("));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/home", name + ".java"));
    }
}
