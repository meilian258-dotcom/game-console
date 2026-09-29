package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.home.HomeApplianceControl.*;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;

class ApplianceControlsTest {
    private static ApplianceRay.Point p(double x,double y,double z) { return new ApplianceRay.Point(x/16,y/16,z/16); }
    @Test void fcPowerResetAndBothDocksUseExportedScale() {
        assertEquals(POWER,ApplianceControls.famicom(p(10.4,30,3.9),p(10.4,0,3.9)));
        assertEquals(RESET,ApplianceControls.famicom(p(5.5,30,3.9),p(5.5,0,3.9)));
        assertEquals(CONTROLLER_ONE,ApplianceControls.famicom(p(12.8,30,8),p(12.8,0,8)));
        assertEquals(CONTROLLER_TWO,ApplianceControls.famicom(p(3.1,30,8),p(3.1,0,8)));
        assertEquals(NONE,ApplianceControls.famicom(p(8,30,8),p(8,0,8)));
    }
    @Test void crtButtonsDoNotTreatScreenOrBackAsPower() {
        assertEquals(POWER,tv(ArcadeDisplayStyle.HOME_RETRO_TV,2.5,2.5));
        assertEquals(VOLUME_UP,tv(ArcadeDisplayStyle.HOME_RETRO_TV,14.55,2.5));
        assertEquals(VOLUME_DOWN,tv(ArcadeDisplayStyle.HOME_RETRO_TV,12.63,2.5));
        assertEquals(NONE,tv(ArcadeDisplayStyle.HOME_RETRO_TV,16,16));
        assertEquals(NONE,ApplianceControls.television(ArcadeDisplayStyle.HOME_RETRO_TV,p(2.5,2.5,40),p(2.5,2.5,-20)));
    }
    @Test void bothSuborModelsUseTheirOwnPowerAndDockCoordinates() {
        assertEquals(POWER,ApplianceControls.subor(false,p(1.6,30,7.8),p(1.6,0,7.8)));
        assertEquals(CONTROLLER_ONE,ApplianceControls.subor(false,p(11,30,4),p(11,0,4)));
        assertEquals(CONTROLLER_TWO,ApplianceControls.subor(false,p(4,30,4),p(4,0,4)));
        assertEquals(POWER,ApplianceControls.subor(true,p(5.3,30,20),p(5.3,0,20)));
        assertEquals(CONTROLLER_ONE,ApplianceControls.subor(true,p(22,30,8),p(22,0,8)));
        assertEquals(CONTROLLER_TWO,ApplianceControls.subor(true,p(9,30,8),p(9,0,8)));
        assertEquals(NONE,ApplianceControls.subor(false,p(1.6,-3,7.8),p(1.6,30,7.8)));
        assertEquals(NONE,ApplianceControls.subor(true,p(5.3,-3,20),p(5.3,30,20)));
    }
    @Test void separateSuborResetWorksInLegacyAndCompactLayouts() {
        assertEquals(RESET,ApplianceControls.subor(false,p(2.25,30,7.8),p(2.25,0,7.8)));
        assertEquals(RESET,ApplianceControls.subor(true,p(6.5,30,20),p(6.5,0,20)));
        double z=HomeConsoleLayout.compactZ(20);
        assertEquals(RESET,ApplianceControls.subor(true,true,p(6.5,30,z),p(6.5,0,z)));
        assertEquals(POWER,ApplianceControls.subor(true,true,p(5.3,30,z),p(5.3,0,z)));
        assertEquals(NONE,ApplianceControls.subor(true,true,p(6.5,-3,z),p(6.5,30,z)));
        double dock=HomeConsoleLayout.compactZ(8);
        assertEquals(CONTROLLER_ONE,ApplianceControls.subor(true,true,p(22,30,dock),p(22,0,dock)));
        assertEquals(CONTROLLER_TWO,ApplianceControls.subor(true,true,p(9,30,dock),p(9,0,dock)));
    }
    @Test void emptyPhysicalFcWellsAreStillClickableAtBothBackplates() {
        assertEquals(CONTROLLER_ONE,ApplianceControls.famicom(p(30,2,9),p(11,2,9)));
        assertEquals(CONTROLLER_TWO,ApplianceControls.famicom(p(-10,2,9),p(5,2,9)));
    }
    @Test void lcdAndVintageHaveSeparateVolumeRegions() {
        for(var s:new ArcadeDisplayStyle[]{ArcadeDisplayStyle.HOME_LCD_TV,ArcadeDisplayStyle.HOME_LARGE_LCD_TV,ArcadeDisplayStyle.HOME_WIDE_LCD_TV}) {
            int dx=s==ArcadeDisplayStyle.HOME_LCD_TV?0:8;
            assertEquals(POWER,tv(s,14+dx,1.25));
            assertEquals(VOLUME_UP,tv(s,12.8+dx,1.25));
            assertEquals(VOLUME_DOWN,tv(s,11.6+dx,1.25));
        }
        assertEquals(POWER,tv(ArcadeDisplayStyle.HOME_VINTAGE_TV,1.8,1.5));
        assertEquals(VOLUME_UP,tv(ArcadeDisplayStyle.HOME_VINTAGE_TV,2.28,6.9));
        assertEquals(VOLUME_DOWN,tv(ArcadeDisplayStyle.HOME_VINTAGE_TV,2.28,6.3));
    }
    private static HomeApplianceControl tv(ArcadeDisplayStyle style,double x,double y) {
        return ApplianceControls.television(style,p(x,y,-20),p(x,y,25));
    }
    @Test void inverseFacingRoundTripsAllFourDirections() {
        var expected=new ApplianceRay.Point(.3,.8,-.2);
        var world=new ApplianceRay.Point[]{expected,new ApplianceRay.Point(1.2,.8,.3),new ApplianceRay.Point(.7,.8,1.2),new ApplianceRay.Point(-.2,.8,.7)};
        for(int i=0;i<4;i++) {var actual=ApplianceRay.unrotate(world[i],i);assertEquals(expected.x(),actual.x(),1e-12);assertEquals(expected.y(),actual.y(),1e-12);assertEquals(expected.z(),actual.z(),1e-12);}
    }
    @Test void rayRejectsNonfiniteZeroLengthAndOutOfSegment() {
        var box=ApplianceRay.units(POWER,0,0,0,16,16,16);
        assertEquals(NONE,ApplianceRay.pick(new ApplianceRay.Point(Double.NaN,0,0),p(0,0,0),box));
        assertEquals(NONE,ApplianceRay.pick(p(8,8,8),p(8,8,8),box));
        assertEquals(NONE,ApplianceRay.pick(p(8,8,-20),p(8,8,-1),box));
        assertEquals(POWER,ApplianceRay.pick(p(8,8,-20),p(8,8,20),box));
    }
    @Test void nearestBoxWinsNotDeclarationOrder() {
        assertEquals(RESET,ApplianceRay.pick(p(8,8,-20),p(8,8,30),ApplianceRay.units(POWER,0,0,10,16,16,12),ApplianceRay.units(RESET,0,0,1,16,16,2)));
    }
}
