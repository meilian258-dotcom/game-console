package cn.piq.fcarcade.cabinet;

/** No credits or game price table: one physical token is one native SELECT edge. */
public final class CabinetCoinPolicy {
    public static final int COIN_MASK=1<<2;
    public static final String BACKEND="piq_native_arcade:mame";
    private CabinetCoinPolicy(){}
    public static boolean supported(String backend){return BACKEND.equals(backend);}
    public static int filter(boolean required,int mask){return required?mask&~COIN_MASK:mask;}
    public static int[][] pulse(int[] masks,int port){
        if(masks==null||masks.length!=4||port<0||port>3)throw new IllegalArgumentException("coin port");
        int[] up=masks.clone();for(int i=0;i<4;i++){if((up[i]&~4095)!=0)throw new IllegalArgumentException("coin mask");up[i]&=~COIN_MASK;}
        int[] down=up.clone();down[port]|=COIN_MASK;return new int[][]{down,up};
    }
}
