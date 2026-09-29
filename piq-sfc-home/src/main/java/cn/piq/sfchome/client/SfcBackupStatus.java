// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

/** Worker-owned, transition-only notices; a request to save is not a successful save. */
final class SfcBackupStatus {
    private int lastSavedFrame=-1;
    private boolean failing;

    String success(int frame){
        if(frame<0)throw new IllegalArgumentException("Negative backup frame");
        lastSavedFrame=frame;
        if(!failing)return null;
        failing=false;
        return "SFC 本机恢复备份已恢复写入；这不是服务器卡带存档，暂不自动读档";
    }

    String failure(){
        if(failing)return null;
        failing=true;
        return lastSavedFrame<0
            ?"SFC 本机恢复备份写入失败：本次会话尚无成功备份；已有历史备份未覆盖，请查看诊断日志"
            :"SFC 本机恢复备份写入失败：保留此前已成功的备份（第 "+lastSavedFrame+" 帧），之后的进度可能丢失；请查看诊断日志";
    }
}
