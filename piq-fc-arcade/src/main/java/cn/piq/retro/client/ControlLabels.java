package cn.piq.retro.client;

/** Human labels follow native input bit order, not physical button-array order. */
final class ControlLabels {
    private ControlLabels() { }
    static String system(String system) {
        return switch (system) { case "NES" -> "FC"; case "SFC" -> "SFC"; default -> "街机"; };
    }
    static String[] nativeButtons(String system) {
        return switch (system) {
            case "NES" -> new String[]{"A", "B", "选择", "开始", "上", "下", "左", "右"};
            case "SFC" -> new String[]{"B", "Y", "选择", "开始", "上", "下", "左", "右", "A", "X", "L", "R"};
            default -> new String[]{"按键 1", "按键 2", "投币", "开始", "上", "下", "左", "右", "按键 3", "按键 4", "按键 5", "按键 6"};
        };
    }
}
