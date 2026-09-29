package cn.piq.fcarcade.client.cabinet;

/** Conservative hysteresis: bounded 20/30 fps adaptation, never changes emulation timing. */
final class CabinetMediaPolicy {
    private int target=20,healthy;
    int sample(boolean automatic,int requested,double bytesPerFrame,double encodeMillis,int queue,long rejected){
        if(!automatic){target=requested==30?30:20;healthy=0;return target;}
        if(queue>2||rejected>0||encodeMillis>10||bytesPerFrame*30+192000>850000){target=20;healthy=0;}
        else if(++healthy>=3)target=30;
        return target;
    }
    int target(){return target;}
}
