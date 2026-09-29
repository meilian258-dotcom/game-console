package cn.piq.nativearcade.bridge;

/** Versioned, bounded, big-endian private parent/child pipe. No network exposure. */
public final class BridgeProtocol {
    private BridgeProtocol(){}
    public static final int MAGIC=0x50495141,VERSION=4,FRAME=1;
    public static final int INPUT=1,CLEAR=2,CLOSE=3,INPUT4=4,RELEASE_PORT=5,RELEASE_GAMEPLAY_KEEP_COIN=6,MAX_DIM=2048,MAX_PIXELS=2048*2048,MAX_PCM=16384;
    public static final long MAX_ROM=64L*1024*1024;
    public static final String CORE_SHA="6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301";
    public static final String JNA_SHA="34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6";
    public static void frameBounds(int width,int height,float aspect,int rotation,int samples)throws java.io.IOException{
        if(width<1||height<1||width>MAX_DIM||height>MAX_DIM||(long)width*height>MAX_PIXELS
            ||!Float.isFinite(aspect)||aspect<.1f||aspect>10||rotation<0||rotation>3
            ||samples<0||samples>MAX_PCM||(samples&1)!=0)throw new java.io.IOException("Invalid native frame header");
    }
}
