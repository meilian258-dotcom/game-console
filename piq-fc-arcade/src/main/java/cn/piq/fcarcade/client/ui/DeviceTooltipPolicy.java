package cn.piq.fcarcade.client.ui;

/** Exact owned-hint matching; unrelated item text must never be hidden. */
final class DeviceTooltipPolicy {
    private DeviceTooltipPolicy() {}
    static boolean detailKey(String kind,String key) {
        return switch(kind) {
            case "cartridge" -> key.equals("item.piq_fc_arcade.fc_cartridge.computer_hint");
            case "video" -> key.equals("tooltip.piq_fc_arcade.av_cable.connect")||key.equals("tooltip.piq_fc_arcade.av_cable.disconnect");
            case "data" -> key.equals("tooltip.piq_fc_arcade.data_cable.connect")||key.equals("tooltip.piq_fc_arcade.data_cable.disconnect")||key.equals("tooltip.piq_fc_arcade.data_cable.permissions");
            default -> false;
        };
    }
    static boolean detailLiteral(String kind,String text) {
        return kind.equals("cartridge")&&(text.equals("主手 Shift + 左键：拆开卡壳（背包需一个空格）")||text.equals("普通右键 FC 主机：插入已写好的卡带"));
    }
}
