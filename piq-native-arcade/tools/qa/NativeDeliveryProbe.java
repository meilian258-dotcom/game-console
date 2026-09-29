import cn.piq.nativearcade.bridge.BridgeProtocol;
import cn.piq.nativearcade.layout.NativeVideoPresentation;
import java.io.IOException;
import java.util.HashSet;

/** Only consumes the supplied final JAR's pure Java API; never initializes a native session. */
public final class NativeDeliveryProbe {
    static int checks;
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError(checks); }
    static void invalid(int w,int h,float a,int r,int n) throws Exception {
        try { BridgeProtocol.frameBounds(w,h,a,r,n); throw new AssertionError("accepted invalid header"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(BridgeProtocol.VERSION == 1 && BridgeProtocol.MAGIC == 0x50495141);
        for (int w : new int[]{1,224,260,2048}) for(int h : new int[]{1,224,260,2048})
            for(int r=0;r<4;r++) { BridgeProtocol.frameBounds(w,h,4f/3,r,16384); checks++; }
        invalid(0,224,1,0,0); invalid(2049,224,1,0,0); invalid(224,0,1,0,0);
        invalid(224,2049,1,0,0); invalid(224,224,Float.NaN,0,0);
        invalid(224,224,Float.POSITIVE_INFINITY,0,0); invalid(224,224,.09f,0,0);
        invalid(224,224,10.1f,0,0); invalid(224,224,1,-1,0); invalid(224,224,1,4,0);
        invalid(224,224,1,0,-2); invalid(224,224,1,0,1); invalid(224,224,1,0,16386);
        float[][] corners={{0,0},{1,0},{1,1},{0,1}};
        for (int r=0;r<4;r++) {
            HashSet<String> reached = new HashSet<>();
            for(float[] c:corners) {
                float[] uv=NativeVideoPresentation.textureUv(c[0],c[1],r);
                check((uv[0]==0||uv[0]==1)&&(uv[1]==0||uv[1]==1));
                reached.add(uv[0]+","+uv[1]);
                float[] restored=NativeVideoPresentation.textureUv(uv[0],uv[1],4-r);
                check(restored[0]==c[0]&&restored[1]==c[1]);
            }
            check(reached.size()==4);
            check(Math.abs(NativeVideoPresentation.displayAspect(4f/3,r)-((r&1)==0?4f/3:3f/4))<.00001f);
        }
        System.out.println("NATIVE_DELIVERY_PROBE="+checks+" PASS");
    }
}
