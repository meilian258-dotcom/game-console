package cn.piq.fcarcade.session;

/** Compact authoritative sensor input; the ROM, not the server, decides hits. */
public final class ZapperInput {
    public static final int NEUTRAL=1<<16;
    private ZapperInput() {}
    public static int pack(int x,int y,boolean offscreen,boolean trigger) {
        if(!offscreen&&(x<0||x>=256||y<0||y>=240))throw new IllegalArgumentException("Gun coordinates outside native screen");
        return (offscreen?NEUTRAL:x|(y<<8))|(trigger?1<<17:0);
    }
    public static int validate(int packed) {
        if((packed&~0x3ffff)!=0 || (offscreen(packed)?(packed&65535)!=0:y(packed)>=240))
            throw new IllegalArgumentException("Noncanonical gun input");
        return packed;
    }
    public static int x(int packed){return packed&255;}
    public static int y(int packed){return(packed>>>8)&255;}
    public static boolean offscreen(int packed){return(packed&NEUTRAL)!=0;}
    public static boolean trigger(int packed){return(packed&(1<<17))!=0;}
}
