package cn.piq.nativearcade.bridge;

/** Host numbered buttons 1..6 use bits 0,1,8,9,10,11.
 * Fixed MAME baseline uses RetroPad B,A,Y,X,L,R = 0,8,1,9,10,11.
 * The private helper is the single conversion boundary, shared by all sources/ports.
 */
public final class NativeArcadeButtons {
    private NativeArcadeButtons(){}
    public static int toMame(int mask){
        NativeInputPorts.checkMask(mask);
        return (mask&~((1<<1)|(1<<8)))|((mask&(1<<1))<<7)|((mask&(1<<8))>>>7);
    }
}
