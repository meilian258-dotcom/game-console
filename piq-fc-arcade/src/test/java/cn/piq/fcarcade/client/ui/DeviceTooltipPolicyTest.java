package cn.piq.fcarcade.client.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceTooltipPolicyTest {
    @Test void hidesOnlyExactOwnedHintsForTheCorrectItem() {
        assertTrue(DeviceTooltipPolicy.detailKey("video","tooltip.piq_fc_arcade.av_cable.connect"));
        assertTrue(DeviceTooltipPolicy.detailKey("data","tooltip.piq_fc_arcade.data_cable.permissions"));
        assertTrue(DeviceTooltipPolicy.detailKey("cartridge","item.piq_fc_arcade.fc_cartridge.computer_hint"));
        assertFalse(DeviceTooltipPolicy.detailKey("video","tooltip.piq_fc_arcade.av_cable.connect.other"));
        assertFalse(DeviceTooltipPolicy.detailKey("cartridge","tooltip.piq_fc_arcade.av_cable.connect"));
        assertFalse(DeviceTooltipPolicy.detailKey("other","tooltip.piq_fc_arcade.data_cable.permissions"));
        assertTrue(DeviceTooltipPolicy.detailLiteral("cartridge","主手 Shift + 左键：拆开卡壳（背包需一个空格）"));
        assertFalse(DeviceTooltipPolicy.detailLiteral("data","主手 Shift + 左键：拆开卡壳（背包需一个空格）"));
        assertFalse(DeviceTooltipPolicy.detailLiteral("cartridge","已写入 ROM · aabbccdd"));
        assertFalse(DeviceTooltipPolicy.detailLiteral("cartridge","自定义物品描述"));
    }
}
