import cn.piq.fcarcade.home.CartridgeComputerLayout;
public class CartridgeComputerLayoutProbe {
    private static void print(String tag,int turn,int index,CartridgeComputerLayout.Bounds b) {
        System.out.printf(java.util.Locale.ROOT,"%s %d %d %.12f %.12f %.12f %.12f %.12f %.12f%n",tag,turn,index,b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());
    }
    public static void main(String[] args) {
        for(int t=0;t<4;t++) {
            print("BOUND",t,-1,CartridgeComputerLayout.bounds(t));int n=0;
            for(var b:CartridgeComputerLayout.parts(t))print("PART",t,n++,b);
        }
    }
}
