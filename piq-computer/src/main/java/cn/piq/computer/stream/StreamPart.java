package cn.piq.computer.stream;

import java.util.UUID;

/** Immutable-by-contract packet data; consumers never change the byte array. */
public record StreamPart(UUID computer,UUID session,long sequence,long micros,int kind,int index,int count,int width,int height,byte[] bytes) {
    public static final int PART=16000,MAX_IMAGE=64000,MAX_AUDIO=2400;
    public StreamPart {
        if(computer==null||session==null||sequence<0||micros<0||kind<0||kind>1||bytes==null||bytes.length<1||bytes.length>PART)
            throw new IllegalArgumentException("Invalid stream packet");
        if(kind==0){
            if(width!=640||height!=480||count<1||count>4||index<0||index>=count||index<count-1&&bytes.length!=PART)
                throw new IllegalArgumentException("Invalid video fragment");
        }else if(index!=0||count!=1||width!=0||height!=0||bytes.length>MAX_AUDIO)throw new IllegalArgumentException("Invalid audio block");
    }
}
