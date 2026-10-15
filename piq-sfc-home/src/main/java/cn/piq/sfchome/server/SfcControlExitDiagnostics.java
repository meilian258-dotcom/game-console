// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.sfchome.server;

/** Only fixed local reasons and numeric runtime facts enter the log, never player/content identities. */
final class SfcControlExitDiagnostics {
    enum Code { HEARTBEAT_TIMEOUT, HEARTBEAT_INVALID, INPUT_INVALID, CABLE_DISTANCE, LEASE_INVALID,
        AUTHORITY_CHANGED, RETURN_REQUEST, DEVICE_REMOVED, REPAIR_FAILED, UNKNOWN_LOCAL_REASON }
    record Exit(Code code,String reason,int port,boolean netplay,boolean activePort,SfcInputHealth.Sample heartbeat) {
        String text(){
            return "code="+code+" reason="+reason+" port="+port+" netplay="+netplay+" activePort="+activePort
                    +(heartbeat==null?" heartbeat=not-active":" tick="+heartbeat.tick()+" lastAcceptedTick="+heartbeat.lastAcceptedTick()
                    +" heartbeatAgeTicks="+heartbeat.ageTicks()+" packetsThisTick="+heartbeat.packetsThisTick());
        }
    }
    private SfcControlExitDiagnostics(){}
    static Exit capture(String reason,int port,boolean netplay,boolean activePort,SfcInputHealth.Sample heartbeat){
        Code code=classify(reason);
        return new Exit(code,code==Code.UNKNOWN_LOCAL_REASON?"未登记的本地退出原因":reason,port,netplay,activePort,activePort?heartbeat:null);
    }
    private static Code classify(String reason){
        if(reason==null)return Code.UNKNOWN_LOCAL_REASON;
        return switch(reason){
            case "手柄连接超时","手柄输入超时，已退出控制；主机继续" -> Code.HEARTBEAT_TIMEOUT;
            case "Netplay 手柄心跳无效" -> Code.HEARTBEAT_INVALID;
            case "输入序列或频率异常，手柄已归还；主机继续" -> Code.INPUT_INVALID;
            case "手柄线超过 6 格，已自动归还；主机继续运行" -> Code.CABLE_DISTANCE;
            case "手柄连接已失效，已自动归还" -> Code.LEASE_INVALID;
            case "手柄或交互权限改变，已退出控制" -> Code.AUTHORITY_CHANGED;
            case "P1 手柄已归还","P2 手柄已归还","手柄已归还，主机继续运行" -> Code.RETURN_REQUEST;
            case "主机已拆除" -> Code.DEVICE_REMOVED;
            case "尚无可用检查点，已退出异常端；主机继续","控制端重复不同步，已退出控制；主机继续",
                    "重同步连接失效","重同步状态无效，已退出异常端；主机继续","重同步状态校验失败；主机继续",
                    "宿主检查点已过期；异常端退出，主机继续","重同步追帧未完成；主机继续",
                    "本端重同步超时或控制权改变；主机继续","本端追帧历史已过期；主机继续" -> Code.REPAIR_FAILED;
            default -> Code.UNKNOWN_LOCAL_REASON;
        };
    }
}
