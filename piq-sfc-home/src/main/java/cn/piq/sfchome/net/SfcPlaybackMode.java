package cn.piq.sfchome.net;

/** Wire mode captured once per session; receiving media never implies emulator authority. */
public final class SfcPlaybackMode {
    public static final int PLAYER_MEDIA=0, LOCAL_SYNC=1, SERVER_MEDIA=2, NETPLAY=3;
    private SfcPlaybackMode(){}
    public static int checked(int mode){
        if(mode<PLAYER_MEDIA||mode>NETPLAY)throw new IllegalArgumentException("Invalid SFC synchronization mode");
        return mode;
    }
    public static boolean receivesMedia(int mode,boolean executionHost){
        checked(mode);return mode==SERVER_MEDIA||mode==PLAYER_MEDIA&&!executionHost;
    }
    public static boolean permitsRom(int mode,boolean executionHost){
        checked(mode);return mode==NETPLAY||mode==LOCAL_SYNC||mode==PLAYER_MEDIA&&executionHost;
    }
    public static boolean checksState(int mode){return checked(mode)==LOCAL_SYNC;}
}
