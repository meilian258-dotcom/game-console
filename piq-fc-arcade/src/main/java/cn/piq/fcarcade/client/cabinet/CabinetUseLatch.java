package cn.piq.fcarcade.client.cabinet;

/** A held power press suppresses more physical presses, never its own server reply. */
final class CabinetUseLatch {
    private boolean repeats, replies;
    void powerPress(boolean down){repeats=down;}
    void exited(boolean down){replies=down;}
    void observe(boolean down){if(!down){repeats=false;replies=false;}}
    boolean blocksInput(){return repeats||replies;}
    boolean blocksReply(){return replies;}
}
