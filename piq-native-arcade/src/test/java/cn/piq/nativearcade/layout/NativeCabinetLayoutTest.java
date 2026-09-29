package cn.piq.nativearcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NativeCabinetLayoutTest {
    @Test void frozenGeometryIsNotRescaled(){assertEquals(1,NativeCabinetLayout.MODEL_SCALE);assertEquals(.35,NativeCabinetLayout.MODEL_Y_OFFSET);}
    @Test void fourFacingsPreserveFrozenPhysicalGlassAndStableCache(){
        var normal=new RocketArcadeGeometry.Point(0,.3826834323650898,-.9238795325112867);
        var lower=new RocketArcadeGeometry.Point(.25,16.55/16+normal.y()*.0015,5.0/16+normal.z()*.0015);
        var upper=new RocketArcadeGeometry.Point(1.75,29.02237368890237/16+normal.y()*.0015,10.166226336928712/16+normal.z()*.0015);
        for(int t=0;t<4;t++){
            var q=NativeCabinetLayout.screen(t);assertEquals(RocketArcadeGeometry.rotate(lower,t),q.lowerMinX());
            assertEquals(RocketArcadeGeometry.rotate(upper,t),q.upperMaxX());assertSame(q,NativeCabinetLayout.screen(t+4));
        }
    }
    @Test void physicalWindowIsExactlyWideWithoutCrop(){var q=NativeCabinetLayout.screen(0);assertEquals(1.5,q.lowerMinX().distanceTo(q.lowerMaxX()),1e-9);assertEquals(.84375,q.lowerMinX().distanceTo(q.upperMinX()),1e-9);}
    @Test void frameMatchesCoreDarIncludingVerticalArcade(){for(int t=0;t<4;t++)for(double ratio:new double[]{4.0/3,3.0/4,1,16.0/9}){var q=NativeCabinetLayout.frame(t,ratio);assertEquals(ratio,q.lowerMinX().distanceTo(q.lowerMaxX())/q.lowerMinX().distanceTo(q.upperMinX()),1e-9);assertEquals(NativeCabinetLayout.screen(t).center().x(),q.center().x(),1e-9);}}
    @Test void boundsAndLabelStayAboveNative235Height(){assertEquals(2.35,NativeCabinetLayout.bounds(0).maxY());assertEquals(2.60,NativeCabinetLayout.occupancy(0).y());}
    @Test void invalidAspectCannotReachRenderer(){for(double r:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->NativeCabinetLayout.frame(0,r));}
}
