package cn.piq.fcarcade.layout;
import cn.piq.fcarcade.home.WideLcdTvLayout;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WideLcdPresentationTest {
    @Test void physicalWideLcdKeepsFourByThreeContentAndSymmetricBars() {
        for (int t=0;t<4;t++) {
            var glass=WideLcdTvLayout.screen(t);var q=WideLcdPresentation.frame(t);
            double w=distance(q.lowerMinX(),q.lowerMaxX()),h=distance(q.lowerMinX(),q.upperMinX());
            assertEquals(4D/3,w/h,1e-10);
            assertEquals(glass.center().x(),q.center().x(),1e-10);
            assertEquals(glass.center().y(),q.center().y(),1e-10);
            assertEquals(glass.center().z(),q.center().z(),1e-10);
            assertEquals(2.75/16,distance(glass.lowerMinX(),q.lowerMinX()),1e-10);
            assertSame(q,WideLcdPresentation.frame(t+4));
        }
    }
    private static double distance(RocketArcadeGeometry.Point a,RocketArcadeGeometry.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
}
