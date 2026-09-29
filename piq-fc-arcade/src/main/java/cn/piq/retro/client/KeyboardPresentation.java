package cn.piq.retro.client;

import java.util.List;
import cn.piq.retro.client.KeyboardConfig.Profile;
import cn.piq.retro.client.KeyboardConfig.Preset;

/** One source for the scheme picker and actual binding preview. No input ownership. */
final class KeyboardPresentation {
    private KeyboardPresentation() { }
    static List<Preset> choices() { return List.of(Preset.CLASSIC, Preset.NUMPAD, Preset.WASD, Preset.CUSTOM, Preset.LEGACY); }
    static String name(Preset preset, Profile profile) {
        return switch(preset) {
            case CLASSIC -> profile==Profile.NES?"方向键 + J / K":"方向键 + JKL / IOP";
            case NUMPAD -> profile==Profile.NES?"方向键 + 小键盘 1 / 2":"方向键 + 小键盘 1–6";
            case WASD -> profile==Profile.NES?"WASD + J / K":"WASD + JKL / IOP";
            case CUSTOM -> "自定义键盘布局";
            case LEGACY -> "跟随 Minecraft 按键设置";
        };
    }
    static String description(Preset preset) {
        return switch(preset) {
            case CLASSIC -> "方向键控制游戏；WASD 可在世界走动，功能键始终优先控制游戏";
            case NUMPAD -> "方向键控制游戏，使用对应小键盘功能键；拿起即生效，可同时在世界走动";
            case WASD -> "推荐：拿起即启用功能键；未锁时 WASD 走路，按位置锁后 WASD 控制游戏";
            case CUSTOM -> "游戏功能键优先；与世界移动重合的方向键只在位置锁内控制游戏";
            case LEGACY -> "跟随原绑定与备用键；热键冲突局部改用空闲键，下方显示实际键位，不改原设置";
        };
    }
    static boolean pending(KeyboardConfig saved, KeyboardConfig draft, Profile profile) {
        return !saved.bindings(profile).equals(draft.bindings(profile))
                || saved.toggleKey()!=draft.toggleKey() || saved.settingsKey()!=draft.settingsKey();
    }
    static String scopeSummary() { return "仅接管本方案；位置锁不屏蔽其余快捷键"; }
    static String scopeDescription(Preset preset) {
        String text="FC、SFC 和街机分别保存方案，切换设备不会自动沿用另一台的方案。\n"
                +"手持有效设备时，本方案的游戏功能键优先交给模拟器，同键的普通世界功能让位。\n"
                +"位置锁只锁定走动、跳跃、潜行等移动；不属于本方案的其它快捷键仍可使用。\n"
                +"与世界移动重合的游戏方向键，未锁时只移动人物；锁定后才控制游戏。";
        if(preset==Preset.NUMPAD)text+="\n本方案使用小键盘数字，不使用 JKL / IOP 作为游戏功能键；位置锁和设置快捷键以本页为准。";
        return text;
    }
    static int[] order(Profile profile) {
        return profile == Profile.NES ? new int[]{4,5,6,7,0,1,2,3}
                : profile == Profile.SFC ? new int[]{4,5,6,7,0,8,1,9,10,11,2,3}
                : new int[]{4,5,6,7,0,1,8,9,10,11,2,3};
    }
    static String shortKey(int key) {
        return switch(key) {
            case 265 -> "↑"; case 264 -> "↓"; case 263 -> "←"; case 262 -> "→";
            case 259 -> "退格"; case 257 -> "回车";
            case 321,322,323,324,325,326 -> "小键盘 " + (key-320);
            default -> null;
        };
    }
}
