// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcSaveSettingsTest {
    @Test void sharedChoiceOrderKeepsExistingSfcOwnershipValues(){
        assertEquals(0,SfcSaveSettings.mode(0));assertEquals(2,SfcSaveSettings.mode(1));assertEquals(1,SfcSaveSettings.mode(2));
        assertEquals("不保存",SfcSaveSettings.label(0));assertEquals("卡带存档",SfcSaveSettings.label(1));assertEquals("个人存档",SfcSaveSettings.label(2));
        assertTrue(SfcSaveSettings.description(0).contains("已有进度保留"));assertTrue(SfcSaveSettings.description(1).contains("实体卡带"));assertTrue(SfcSaveSettings.description(2).contains("开机玩家"));
        assertThrows(IllegalArgumentException.class,()->SfcSaveSettings.mode(-1));assertThrows(IllegalArgumentException.class,()->SfcSaveSettings.mode(3));
    }
}
