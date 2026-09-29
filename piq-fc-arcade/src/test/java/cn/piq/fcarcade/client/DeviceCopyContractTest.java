package cn.piq.fcarcade.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Player-facing copy stays concise without hiding authority or unsupported features. */
class DeviceCopyContractTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + name + ".java"));
    }

    private static JsonObject language(String name) throws Exception {
        return JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/piq_fc_arcade/lang/" + name + ".json"))).getAsJsonObject();
    }

    @Test void adminToolsDescribeRequiredAuthorityWithoutGamemodeComparisons() throws Exception {
        for (String locale : List.of("zh_cn", "en_us")) {
            JsonObject lang = language(locale);
            for (String key : List.of("tooltip.piq_fc_arcade.admin_terminal", "tooltip.piq_fc_arcade.debug_screwdriver")) {
                String text = lang.get(key).getAsString();
                assertTrue(text.contains("OP"), key);
                for (String obsolete : List.of("生存", "创造", "survival", "creative"))
                    assertFalse(text.toLowerCase(Locale.ROOT).contains(obsolete), key);
            }
        }
        String terminal = source("client/AdminTerminalScreen");
        assertFalse(terminal.contains("生存模式"));
        assertTrue(terminal.contains("服务器管理 · 需 OP"));
        assertTrue(terminal.contains("仅影响新机器，已有机器不变。"));
    }

    @Test void conciseDevicePagesKeepPendingAndUnsupportedMessages() throws Exception {
        String home = source("client/HomeSyncSettingsScreen");
        assertTrue(home.contains("等待服务器确认…"));
        assertTrue(home.contains("保存未确认，请关闭后重开。"));
        assertTrue(home.contains("此机型不支持使用者标牌。"));
        assertTrue(home.contains("已借手柄无需归还"));
        String cabinet = source("client/cabinet/CabinetSyncSettingsScreen");
        assertTrue(cabinet.contains("FC 街机仅支持本地输入同步。"));
        assertTrue(cabinet.contains("本地输入同步：kof97 / mslug2，最多两席。"));
        assertTrue(cabinet.contains("切换模式需管理员权限，且机器空闲。"));
        assertTrue(cabinet.contains("设置未确认，请关闭后重开。"));
    }

    @Test void privateAndNetworkPagesStillExplainDataBoundaries() throws Exception {
        String privatePage = source("client/PrivateHomeScreen");
        for (String detail : List.of("其他玩家不能加入", "存档留在本机，不上传", "手柄借还仍与服务器同步"))
            assertTrue(privatePage.contains(detail), detail);
        String network = source("client/NetworkDiagnosticsScreen");
        for (String detail : List.of("不含旧SFC街机core9协议", "断开或换服务器清零", "成功编码/解码", "压缩前载荷", "非渲染帧率"))
            assertTrue(network.contains(detail), detail);
    }

    @Test void conciseItemsKeepCableCostAndSaveLossWarnings() throws Exception {
        JsonObject zh = language("zh_cn"), en = language("en_us");
        assertTrue(zh.get("tooltip.piq_fc_arcade.data_cable.connect").getAsString().contains("消耗一根线"));
        assertTrue(en.get("tooltip.piq_fc_arcade.data_cable.connect").getAsString().contains("uses one cable"));
        for (String key : List.of("message.piq_fc_arcade.home_card_ejected", "message.piq_fc_arcade.home_wire_disconnected"))
            assertTrue(zh.get(key).getAsString().contains("可能丢最后片刻"), key);
        assertTrue(zh.get("message.piq_fc_arcade.skin_incompatible_preserved").getAsString().contains("已保留"));
        assertTrue(zh.get("screen.piq_fc_arcade.delete_save_message").getAsString().contains("永久删除"));
        assertTrue(zh.get("screen.piq_fc_arcade.replace_save_message").getAsString().contains("确定删除"));
    }
}
