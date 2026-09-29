package cn.piq.retro.client;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardPresentationTest {
    @Test void allSchemesHaveExplicitNamesAndEveryNativeActionIsVisible(){
        assertEquals(KeyboardConfig.Preset.CLASSIC,KeyboardPresentation.choices().getFirst());
        assertEquals(KeyboardConfig.Preset.values().length,KeyboardPresentation.choices().size());
        for(var profile:KeyboardConfig.Profile.values()){
            var order=KeyboardPresentation.order(profile);
            assertEquals(profile.bits,order.length);
            assertEquals(profile.bits,Arrays.stream(order).distinct().count());
            for(int bit:order)assertTrue(bit>=0&&bit<profile.bits);
            for(var preset:KeyboardPresentation.choices()){
                String name=KeyboardPresentation.name(preset,profile);
                assertFalse(name.contains("现有"));assertFalse(name.isBlank());
                assertFalse(KeyboardPresentation.description(preset).isBlank());
            }
        }
    }
    @Test void numericKeypadCannotBeMistakenForHotbarKeys(){
        for(int i=1;i<=6;i++)assertEquals("小键盘 "+i,KeyboardPresentation.shortKey(320+i));
        assertNull(KeyboardPresentation.shortKey(49));
        assertEquals("退格",KeyboardPresentation.shortKey(259));
        assertEquals("回车",KeyboardPresentation.shortKey(257));
        assertEquals("方向键 + 小键盘 1 / 2",KeyboardPresentation.name(KeyboardConfig.Preset.NUMPAD,KeyboardConfig.Profile.NES));
        assertEquals("方向键 + 小键盘 1–6",KeyboardPresentation.name(KeyboardConfig.Preset.NUMPAD,KeyboardConfig.Profile.SFC));
    }
    @Test void savedAndDraftAreDistinguishedPerDeviceWithoutChangingExistingLayouts(){
        var saved=KeyboardConfig.defaults().withPreset(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.NUMPAD);
        var draft=saved.withPreset(KeyboardConfig.Profile.NES,KeyboardConfig.Preset.CLASSIC);
        assertFalse(KeyboardPresentation.pending(saved,saved,KeyboardConfig.Profile.SFC));
        assertTrue(KeyboardPresentation.pending(saved,draft,KeyboardConfig.Profile.NES));
        assertFalse(KeyboardPresentation.pending(saved,draft,KeyboardConfig.Profile.SFC));
        assertEquals(KeyboardConfig.Preset.NUMPAD,saved.bindings(KeyboardConfig.Profile.SFC).preset());
        assertEquals(KeyboardConfig.Preset.NUMPAD,draft.bindings(KeyboardConfig.Profile.SFC).preset());
        var hotkeyDraft=saved.withHotkeys(77,296);
        for(var profile:KeyboardConfig.Profile.values())assertTrue(KeyboardPresentation.pending(saved,hotkeyDraft,profile));
        var custom=saved.withCustom(KeyboardConfig.Profile.SFC,KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.CLASSIC));
        assertTrue(KeyboardPresentation.pending(saved,custom,KeyboardConfig.Profile.SFC));
        assertEquals(KeyboardConfig.Preset.NUMPAD,saved.bindings(KeyboardConfig.Profile.SFC).preset());
    }
    @Test void scopeExplainsUnrelatedShortcutsAndNumericSchemeDoesNotAdvertiseLetterButtons(){
        assertTrue(KeyboardPresentation.scopeSummary().contains("位置锁不屏蔽"));
        for(var preset:KeyboardConfig.Preset.values()){
            var scope=KeyboardPresentation.scopeDescription(preset);
            assertTrue(scope.contains("分别保存"));
            assertTrue(scope.contains("其它快捷键仍可使用"));
        }
        assertTrue(KeyboardPresentation.scopeDescription(KeyboardConfig.Preset.NUMPAD).contains("不使用 JKL / IOP"));
        var keys=KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,KeyboardConfig.Preset.NUMPAD);
        assertFalse(keys.contains(75)); // K must not be advertised as an active SFC NUMPAD key.
        assertEquals(322,keys.get(8).intValue()); // A is numeric keypad 2, not K.
    }
}
