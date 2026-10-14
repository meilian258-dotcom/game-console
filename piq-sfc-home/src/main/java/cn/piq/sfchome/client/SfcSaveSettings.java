// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Shared page order, adapted to SFC's existing NBT values (personal=1, card=2). */
final class SfcSaveSettings {
    private SfcSaveSettings(){}
    static int mode(int choice){return switch(choice){case 0->0;case 1->2;case 2->1;default->throw new IllegalArgumentException("choice");};}
    static String label(int choice){return switch(mode(choice)){case 0->"不保存";case 2->"卡带存档";default->"个人存档";};}
    static String description(int choice){return switch(mode(choice)){
        case 0->"不读取或写入本次 JNI Netplay 进度；已有进度保留。";
        case 2->"进度属于当前实体卡带与游戏；拿到同一卡带可以继续。";
        default->"进度属于开机玩家与游戏；不同玩家独立保存。";
    };}
}
