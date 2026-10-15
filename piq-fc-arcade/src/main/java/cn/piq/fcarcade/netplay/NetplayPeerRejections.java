// SPDX-License-Identifier: GPL-3.0-or-later
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.netplay;

/** Bounded, path-free rejection evidence. Never retains a peer identity or raw exception text. */
final class NetplayPeerRejections {
    enum Code {
        IDENTITY("核心、内容或选项身份不匹配"), WIRE_SEQUENCE("数据包序列或授权席位异常"),
        INPUT_SEQUENCE("2P 输入帧越界、重复或预输入超限"), STATE_DIGEST("确认状态 CRC 或确认窗口不匹配"),
        PHASE("当前联机阶段不接受该消息"), INPUT_DEVICE("输入设备或端口不匹配"),
        MALFORMED("联机消息格式或字段无效"), PREPARATION_TIMEOUT("种子准备超时"), INPUT_TIMEOUT("2P 输入超时");
        final String reason;
        Code(String reason){this.reason=reason;}
    }
    record Event(Code code,int port,long confirmed,long next) {
        String text(){return "code="+code+" reason="+code.reason+" port="+port+" confirmed="+confirmed+" next="+next;}
    }
    record Snapshot(long count,Event first,Event last) {
        String text(){return count==0?"主持拒绝记录：0":"主持拒绝记录：count="+count+" first={"+first.text()+"} last={"+last.text()+"}";}
    }
    private long count;
    private Event first,last;
    synchronized Snapshot record(Code code,int port,long confirmed,long next){
        var event=new Event(java.util.Objects.requireNonNull(code),port,confirmed,next);
        if(count<Long.MAX_VALUE)count++;
        if(first==null)first=event;
        last=event;return snapshot();
    }
    synchronized Snapshot snapshot(){return new Snapshot(count,first,last);}
    /** First, second, fourth, ... reports keep repeated hostile packets from flooding logs. */
    static boolean shouldLog(long count){return count>0&&(count&(count-1))==0;}
    static Code classify(IllegalArgumentException failure){
        String reason=failure.getMessage();
        if(reason==null)return Code.MALFORMED;
        if(reason.equals("JNI Netplay 包序列或授权席位异常"))return Code.WIRE_SEQUENCE;
        if(reason.equals("2P 输入帧越界或重放")||reason.equals("2P 预输入超限"))return Code.INPUT_SEQUENCE;
        if(reason.startsWith("JNI Netplay 确认状态 CRC")||reason.equals("CRC 不在确认窗口或重复"))return Code.STATE_DIGEST;
        if(reason.equals("JNI Netplay 当前阶段不接受此消息")||reason.equals("重复 2P 授权"))return Code.PHASE;
        if(reason.equals("输入与房间设备不匹配")||reason.equals("需要服务器已授权的端口帧")||reason.equals("输入超过核心真实端口数"))return Code.INPUT_DEVICE;
        return Code.MALFORMED;
    }
}
