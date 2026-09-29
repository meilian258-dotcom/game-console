import cn.piq.fcarcade.home.VintageTvLayout;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.ScreenQuad;
public final class VintageTvPresentationProbe {
    private static void point(Point p){System.out.print(" "+p.x()+" "+p.y()+" "+p.z());}
    private static void quad(ScreenQuad q){point(q.lowerMinX());point(q.lowerMaxX());point(q.upperMaxX());point(q.upperMinX());point(q.normal());}
    public static void main(String[] args){for(int t=0;t<4;t++){
        System.out.print("FRAME "+t);quad(VintageTvLayout.screen(t));System.out.println(" "+(VintageTvLayout.screen(t)==VintageTvLayout.screen(t)));
        System.out.print("SOCKETS "+t);for(int c=0;c<3;c++)point(VintageTvLayout.socket(t,c));System.out.println();
    }}
}
