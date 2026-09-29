package cn.piq.fcarcade.client.ui;

import java.util.Set;

/** Exact allow-list of our obsolete routine messages; unknown and invitation messages are not suppressed. */
public final class DeviceNoticePolicy {
    private static final Set<String> ERROR_KEYS = Set.of(
        "tv_remote_denied", "computer_denied", "dual_cabinet_denied", "controller_invalid", "controller_unavailable",
        "storage_unavailable", "run_failed", "snapshot_failed", "rom_unavailable", "rom_transfer_failed", "rom_transfer_timeout",
        "open_folder_failed", "save_load_failed", "rom_delete_missing", "save_delete_missing", "skin_upload_failed",
        "skin_missing", "skin_transfer_failed", "home_denied", "home_wire_first_missing", "appliance_power_failed", "zapper_stand_denied",
        "home_wire_dimension", "home_wire_same_type", "home_wire_range", "home_wire_occupied", "zapper_stand_changed",
        "zapper_stand_full", "zapper_stand_wrong_gun", "zapper_stand_busy", "zapper_stand_fc_only", "zapper_stand_stop_first",
        "zapper_stand_already_linked", "furniture.occupied", "furniture.space", "furniture.incomplete");
    private static final Set<String> ROUTINE_KEYS = Set.of(
        "controller_hint", "controller_already_owned", "controller_taken", "controller_returned", "controller_p1_returned",
        "controller_p2_returned", "controller_p2_stopped", "controller_p1_stopped", "controller_wrong_console",
        "control_started", "control_stopped", "control_lost", "session_joined", "session_left", "synchronizing", "synchronized", "save_loaded",
        "appliance_console_hint", "appliance_tv_hint", "appliance_tv_on", "appliance_tv_off", "appliance_volume",
        "appliance_console_on", "appliance_console_off", "appliance_choose_save", "home_card_inserted", "home_use_console",
        "home_edit_cartridge", "home_swap_cartridge", "zapper_bind_hint", "zapper_fire_hint", "zapper_stand_returned",
        "zapper_stand_taken", "zapper_stand_take_no_input",
        "zapper_stand_use_gun", "tv_remote_on", "tv_remote_off", "audio_restored", "home_wire_selected", "home_wire_connected",
        "home_wire_disconnected", "home_wire_selection_cleared", "zapper_stand_selected", "zapper_stand_connected", "zapper_stand_disconnected",
        "furniture.folded", "furniture.riding", "furniture.howto");
    private static final Set<String> ROUTINE_TEXT = Set.of(
        "侧面开关键：整组街机已关闭", "正面开关键：整组街机已关闭",
        "街机启动中… 可自由移动和转动视角；右键本机结束", "已退出模拟器", "机柜已卸载或拆除", "已退出原生街机",
        "已结束街机；再次右键启动，Shift 右键配置游戏", "已结束街机；再次右键启动，Shift 空手右键配置游戏",
        "已退出街机；再次右键加入或启动", "已退出街机", "已切换模拟器", "机柜已拆除或卸载", "玩家已离线",
        "正在取得共享游戏并准备本地同步；追赶完成前不会授予输入",
        "正在启动 GBA；游戏、电池存档仅在本机", "掌机已关闭；电池存档正在本机后台收尾", "掌机已退出：手持物品、玩家或连接发生变化",
        "已归还 SFC 控制，主机仍在后台运行", "已切换到 FC 控制，SFC 手柄已归还",
        "主机已关机；手柄仍保留", "电视连接已停止；手柄仍保留", "主机已重置", "卡带已取出",
        "请到主机归还手柄。", "手柄已取下；按主机电源开机，开机后点击主机手柄申请控制。",
        "光枪已归还；主机继续运行。", "请到主机或光枪支架归还光枪。",
        "光枪已领取或等待主机批准；批准后可射击，并在 P1 空闲时操作游戏按键。",
        "请按主机电源开机，再点击主机上的 P1/P2 手柄领取。",
        "卡带已插入；按主机电源开机", "请瞄准电源、重置键或桌上的手柄操作",
        "手柄已借出，等待主机就绪后再点手柄接入控制", "点原手柄位置接入控制；右键主机机身归还，收进物品栏只暂停输入",
        "手柄已借出；按主机电源开机，右键主机机身归还", "请右键绑定主机的机身归还手柄", "请右键这个手柄绑定主机的机身",
        "已经持有这台主机的手柄；关机时只借出，不会启动游戏", "已重新拿起原手柄，继续本局", "已拿起原手柄；请按主机电源开机",
        "请从个人物品栏拿起原手柄", "SFC 正在开机；未领取手柄时只播放，不占用操作键",
        "SFC 已运行；未领取手柄只观看，归还手柄不关机", "手柄已归还，主机继续运行", "本端已恢复到主机进度，控制已恢复",
        "本端正在修复同步，其他玩家继续运行", "检测到本端状态偏差，正在修复；其他玩家继续运行", "正在同步主机当前进度，请稍候",
        "同步申请者：已短暂停表，正在传送当前运行状态",
        "街机通讯线已断开；两台外观保持不变。", "这台街机已经接线；Shift 右键可拆线。",
        "已选主街机；60 秒内右键另一台单人或双人街机，按两柜实际席位连接。");
    private static final Set<String> ERROR_TEXT = Set.of(
        "只有管理员可以连接或拆开街机通讯线。", "机柜不可用，或没有操作权限。", "这台街机没有连接通讯线。",
        "请靠近两台机柜之间再拆线，并确认两端均可操作。", "请先结束两台街机的游戏，再拆开通讯线。",
        "请先结束这台街机的游戏，再连接通讯线。", "主柜模拟器的联机端口不足；请先选择支持本次机柜组合的附属模拟器，旧 FC 路径不支持此通讯线。",
        "通讯线选择繁忙，请稍后重试。", "请选择同一维度、16 格内的另一台街机。", "首台机柜已失效；请靠近两台之间并重新选取，两端都需在 8 格内。",
        "机柜状态在权限检查期间改变，请重试。", "请先结束两台街机的游戏，再连接通讯线。",
        "无法连接：模拟器不足两柜实际席位数、机柜已有连接，或已达到 128 对上限。", "连接未完成，通讯线已取消；请检查机柜状态后重试。",
        "请先退出当前街机，再使用其他机柜。", "请先正常结束当前模拟器会话。", "通讯线另一端未加载或不可用，请先恢复两台机柜。",
        "此附属未支持已配置的本地同步模式，请在空闲时改为音画串流。", "此模拟器的控制席位不支持当前通讯线组合，请先断开通讯线。",
        "通讯线另一端暂不可用。", "街机联机房间已满，请稍后重试。", "这台街机的玩家席位已满。",
        "本次交互已取消", "卡带包含不支持的附加数据", "此手柄未绑定当前主机或交互权限失效", "没有插入卡带",
        "请操作原手柄的插口，或先归还已有手柄", "此卡带不启用这个控制端口；手柄仍保留", "该手柄已借出，或请腾出一只手",
        "这张卡带只允许一个控制端口", "此手柄已有人使用，或正在同步另一位玩家", "手柄已失效", "本次归还已取消",
        "请放下光标中的物品并腾出一只手", "请先插入已写入游戏的 SFC 卡带", "请先连接电视的视频线，再按主机电源",
        "主机或电视的交互权限已失效", "请先关闭正在托管的主机或归还另一台主机的手柄",
        "输入序列或频率异常，手柄已归还；主机继续", "尚无可用检查点，已退出异常端；主机继续", "控制端重复不同步，已退出控制；主机继续",
        "运行端加载超时，主机已停止", "手柄输入超时，已退出控制；主机继续");
    private DeviceNoticePolicy() {}
    public static boolean routineKey(String key) {
        if (key == null) return false;
        if (key.equals("screen.piq_fc_arcade.audio_muted")) return true;
        String prefix = "message.piq_fc_arcade.";
        return key.startsWith(prefix) && ROUTINE_KEYS.contains(key.substring(prefix.length()));
    }
    public static boolean routineText(String message) {
        if (message == null || message.isBlank()) return true;
        String value = message.startsWith("[SFC] ") ? message.substring(6) : message;
        if (ROUTINE_TEXT.contains(value)) return true;
        if(value.equals("最后一名玩家已退出操作，街机已关闭"))return true;
        return value.matches("所有操作席已空闲 [0-9]{1,4} 秒，街机已自动关机")
            || value.matches("P[1-4] 手柄已归还[。]|P[1-4] 手柄已归还；主机继续运行[。]")
            || value.matches("P[12] 手柄已归还，主机继续运行")
            || value.matches("已借出 P[12] 手柄；不会自动开机，请按主机电源")
            || value.matches("已领取 P[12]；归还只退出控制，主机继续运行")
            || value.matches("已接入 P[12] 控制")
            || value.matches("已加入街机 P[1-4]；再次右键退出")
            || value.matches("已取下 P[12] 手柄；按电源才会开机。")
            || value.matches("已连接 [234] 席：副柜从 P[23] 开始；两台机柜外观各自保留。")
            || value.matches("P[12] 已从当前进度加入；归还手柄不会关机");
    }
    public static boolean errorText(String message) {
        if (message == null) return false;
        return ERROR_TEXT.contains(message.startsWith("[SFC] ") ? message.substring(6) : message);
    }
    public static String buttonLabel(String control, boolean powered) {
        return "RESET".equals(control) ? "右键 · 重置"
                : "POWER".equals(control) ? powered ? "右键 · 关机" : "右键 · 开机" : null;
    }
    /** Only known errors. In particular a rejected invitation is a workflow result, not a runtime error. */
    public static boolean errorKey(String key) {
        if (key == null) return false;
        String prefix = "message.piq_fc_arcade.";
        return key.startsWith(prefix) && ERROR_KEYS.contains(key.substring(prefix.length()))
                || key.equals("screen.piq_fc_arcade.audio_failed") || key.equals("screen.piq_fc_arcade.load_failed")
                || key.equals("screen.piq_fc_arcade.run_failed");
    }
}
