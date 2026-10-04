package cn.piq.mdhome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MdControllerGeometryTest {
    @Test void canonicalBindingsUseMdLabelsNotCoreRetroPadOrder() {
        int[] expected={0,0,1,256,2048,2,512,1024,8,4};
        for(int part=0;part<expected.length;part++)assertEquals(expected[part],MdControllerGeometry.mask(part));
        assertEquals(10,MdControllerGeometry.NAMES.size());
    }
    @Test void onlyKeyCapsDepressAndAllReturnExactlyToRest() {
        for(int part=0;part<MdControllerGeometry.PARTS;part++) {
            var pressed=MdControllerGeometry.sample(part,1,0,0,0,0);
            var release=MdControllerGeometry.sample(part,0,0,0,0,0);
            assertEquals(MdControllerGeometry.Transform.REST,release);
            if(part<2)assertEquals(release,pressed);
            else if(part==MdControllerGeometry.MODE) { assertEquals(0,pressed.y());assertEquals(-.06,pressed.z()); }
            else { assertTrue(pressed.y()<0);assertEquals(0,pressed.z()); }
            assertEquals(0,pressed.pitch());assertEquals(0,pressed.roll());
        }
    }
    @Test void smoothIntermediateSamplesHaveHalfTravelWithoutMovingBody() {
        for(int part=2;part<MdControllerGeometry.PARTS;part++) {
            var full=MdControllerGeometry.sample(part,1,0,0,0,0);
            var half=MdControllerGeometry.sample(part,.5,0,0,0,0);
            assertEquals(full.y()/2,half.y());assertEquals(full.z()/2,half.z());
        }
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(0,1,1,1,1,1));
    }
    @Test void fourDirectionsTiltTowardPressedEndpointAndOppositesCancel() {
        assertEquals(5,MdControllerGeometry.sample(1,0,1,0,0,0).pitch());
        assertEquals(-5,MdControllerGeometry.sample(1,0,0,1,0,0).pitch());
        assertEquals(-5,MdControllerGeometry.sample(1,0,0,0,1,0).roll());
        assertEquals(5,MdControllerGeometry.sample(1,0,0,0,0,1).roll());
        assertEquals(new MdControllerGeometry.Transform(0,0,5,-5),MdControllerGeometry.sample(1,0,1,0,1,0));
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(1,0,1,1,1,1));
    }
    @Test void invalidVisualValuesCannotExplodeOrSinkGeometry() {
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(2,Double.NaN,0,0,0,0));
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(2,Double.POSITIVE_INFINITY,0,0,0,0));
        assertEquals(MdControllerGeometry.sample(2,1,0,0,0,0),MdControllerGeometry.sample(2,42,0,0,0,0));
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(2,-1,0,0,0,0));
        assertEquals(MdControllerGeometry.Transform.REST,MdControllerGeometry.sample(1,0,Double.NaN,Double.POSITIVE_INFINITY,-1,0));
    }
}
