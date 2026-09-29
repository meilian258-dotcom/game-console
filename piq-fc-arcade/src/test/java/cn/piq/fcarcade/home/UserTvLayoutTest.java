package cn.piq.fcarcade.home;
import cn.piq.fcarcade.layout.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.layout.ArcadeDisplayStyle.*;

class UserTvLayoutTest {
    private static final ArcadeDisplayStyle[] STYLES={HOME_GRAY_CRT,HOME_RED_CRT,HOME_PANEL_2,HOME_PANEL_2_WALL,HOME_PANEL_3,HOME_PANEL_3_WALL};
    @Test void lcdCentreBezelIsPowerButScreenAndRearAreNot(){
        for(var style:STYLES)if(UserTvLayout.panel(style)){
            double x=UserTvLayout.width(style)*.5,y=3.35/16,z=(UserTvLayout.wall(style)?11.15:7.15)/16;
            assertEquals(HomeApplianceControl.POWER,ApplianceControls.television(style,new ApplianceRay.Point(x,y,-2),new ApplianceRay.Point(x,y,z)));
            assertEquals(HomeApplianceControl.NONE,ApplianceControls.television(style,new ApplianceRay.Point(x,y,2),new ApplianceRay.Point(x,y,-2)));
            assertEquals(HomeApplianceControl.NONE,ApplianceControls.television(style,new ApplianceRay.Point(x,.7,-2),new ApplianceRay.Point(x,.7,z)));
        }
    }
    @Test void originalSocketCoordinatesAreNotScaledOrShiftedTwice(){
        assertEquals(6D/16,UserTvLayout.socket(HOME_GRAY_CRT,0,0).x(),1e-9);
        assertEquals(13.46/16,UserTvLayout.socket(HOME_RED_CRT,0,0).z(),1e-9);
        assertEquals(38.8/16,UserTvLayout.socket(HOME_PANEL_3_WALL,0,0).x(),1e-9);
        assertEquals(4D/16,UserTvLayout.socket(HOME_PANEL_2_WALL,0,2).z()-UserTvLayout.socket(HOME_PANEL_2,0,2).z(),1e-9);
    }
    @Test void allRotationsRetainFourThreeImageAndCorrectSurface(){
        for(var style:STYLES)for(int t=0;t<4;t++){
            var q=UserTvLayout.screen(style,t);
            var shared=ScreenSurfaceGeometry.frame(style,t,1,1,false,ScreenAspectFit.Aspect.FOUR_THREE).image();
            assertEquals(q,shared);
            double width=Math.sqrt(Math.pow(q.lowerMaxX().x()-q.lowerMinX().x(),2)+Math.pow(q.lowerMaxX().z()-q.lowerMinX().z(),2));
            assertEquals(4D/3,width/(q.upperMinX().y()-q.lowerMinX().y()),1e-8);
            assertEquals(1,Math.sqrt(q.normal().x()*q.normal().x()+q.normal().z()*q.normal().z()),1e-9);
            var b=UserTvLayout.bounds(style,t);assertTrue(b.minX()<b.maxX()&&b.minY()<b.maxY()&&b.minZ()<b.maxZ());
            assertEquals(0,ScreenSurfaceGeometry.translation(style,t,true).x());
        }
    }
    @Test void threeWideBakingCompensationIsOnlyOneBlockAndRotatesWithTheModel(){
        assertEquals(new RocketArcadeGeometry.Point(1,0,0),UserTvLayout.modelOffset(HOME_PANEL_3,0));
        assertEquals(new RocketArcadeGeometry.Point(0,0,1),UserTvLayout.modelOffset(HOME_PANEL_3,1));
        assertEquals(new RocketArcadeGeometry.Point(0,0,0),UserTvLayout.modelOffset(HOME_PANEL_2,1));
    }
    @Test void visibleButtonsWorkFromTheFrontButNotThroughRearCase(){
        for(var style:STYLES){
            double x,y,z;
            if(UserTvLayout.panel(style)){x=28.985+(UserTvLayout.width(style)==3?16:0);y=2.995;z=UserTvLayout.wall(style)?11.49:7.49;}
            else if(style==HOME_GRAY_CRT){x=1.29;y=1.315;z=.77;}
            else{x=1.81;y=3.55;z=1.15;}
            assertEquals(HomeApplianceControl.POWER,ApplianceControls.television(style,new ApplianceRay.Point(x/16,y/16,-2),new ApplianceRay.Point(x/16,y/16,(z+1)/16)));
            assertEquals(HomeApplianceControl.NONE,ApplianceControls.television(style,new ApplianceRay.Point(x/16,y/16,2),new ApplianceRay.Point(x/16,y/16,-2)));
        }
    }
    @Test void retiredModelResourceNamesStillExist() throws Exception{
        for(String name:new String[]{"lcd_tv","wide_lcd_tv","large_lcd_tv","vintage_tv"})
            try(var in=getClass().getResourceAsStream("/assets/piq_fc_arcade/models/item/"+name+".json")){assertNotNull(in);}
    }
}
