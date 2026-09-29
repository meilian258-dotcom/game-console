import cn.piq.fcarcade.layout.DualScreenPresentation;
import cn.piq.fcarcade.layout.ScreenAspectFit;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;

/** Prints actual cached production frames, never an independent implementation of fitting. */
public final class DualScreenPresentationProbe {
    private static void point(Point p) { System.out.print(" "+p.x()+" "+p.y()+" "+p.z()); }
    public static void main(String[] args) {
        for (var aspect:ScreenAspectFit.Aspect.values()) for (int turn=0;turn<4;turn++) {
            var q=DualScreenPresentation.frame(turn,aspect);
            System.out.print("FRAME "+aspect.label()+" "+turn);
            point(q.lowerMinX());point(q.lowerMaxX());point(q.upperMaxX());point(q.upperMinX());point(q.normal());
            System.out.println(" "+(q==DualScreenPresentation.frame(turn,aspect)));
        }
    }
}
