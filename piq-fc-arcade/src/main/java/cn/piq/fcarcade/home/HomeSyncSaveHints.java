package cn.piq.fcarcade.home;

/** Presentation only: describes each implemented save lane without adding wire capabilities. */
public final class HomeSyncSaveHints {
    private static final String FOOTER = "仅当前主机 · ";
    private HomeSyncSaveHints() {}

    public static String modeSaved(String system, int mode) {
        if(fc(system)&&mode==4)return "FC JNI Netplay 试验已选择；仅普通双手柄。每位参与/旁观玩家须本机确认风险；独立试验档。";
        if(sfc(system)&&mode==3)return "Netplay 实验已启用；卡带工作台可选个人／卡带存档，主持 P1、另一玩家 P2。";
        if(fc(system)&&mode==3)return "Netplay 实验已启用；支持手柄或光枪，按卡带设置保存，Netplay 档与旧模式隔离。";
        String detail;
        if (sfc(system) && (mode == 0 || mode == 1))
            detail = "此模式仅提供运行玩家本机的恢复备份；不是卡带存档，不会自动续玩。";
        else if (sfc(system) && mode == 2)
            detail = "此模式使用独立服务器 SRAM；与玩家本机恢复备份分开，不会自动转换。";
        else if (fc(system) && validMode(mode))
            detail = "存档方式由卡带决定；光枪使用独立进度。";
        else detail = "存档支持与恢复方式以对应机型说明为准。";
        return "已保存；下次开机生效。" + detail;
    }

    public static String footer(String system, int mode) {
        if(fc(system)&&mode==4)return FOOTER+"JNI 试验：按个人/卡带归属另存 · /gameconsole-jni-netplay 确认";
        if(sfc(system)&&mode==3)return FOOTER+"Netplay 实验：个人／卡带独立存档 · Windows x64";
        if(fc(system)&&mode==3)return FOOTER+"Netplay 实验：按卡带存档设置执行 · Windows x64";
        if (sfc(system) && (mode == 0 || mode == 1))
            return FOOTER + "SFC：本机恢复备份（非卡带档，不自动续玩）";
        if (sfc(system) && mode == 2)
            return FOOTER + "SFC：独立服务器 SRAM";
        if (fc(system) && validMode(mode)) return FOOTER + "存档仍在卡带菜单";
        return FOOTER + "存档功能以对应主机说明为准";
    }

    private static boolean sfc(String system) { return "SFC".equalsIgnoreCase(system); }
    private static boolean fc(String system) {
        return "FC".equalsIgnoreCase(system) || "小霸王学习机（SB-926）".equals(system);
    }
    private static boolean validMode(int mode) { return mode >= 0 && mode <= 2; }
}
