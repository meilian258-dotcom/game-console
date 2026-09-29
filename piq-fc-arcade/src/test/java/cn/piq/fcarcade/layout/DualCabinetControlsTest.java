package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetControlsTest {
    @Test void sixteenDistinctMovableGroupsLeaveRubberBasesStatic(){
        var names=new HashSet<String>();
        for(var p:DualCabinetControls.PARTS){assertTrue(names.add(p.name()));assertFalse(p.name().contains("base"));}
        assertEquals(16,names.size());
    }
    @Test void pivotsExactlyMatchUserTwoPlayerSpacing(){
        for(int i=0;i<8;i++){
            var a=DualCabinetControls.PARTS.get(i);var b=DualCabinetControls.PARTS.get(i+8);
            assertEquals(12,a.x()-b.x(),1e-12);assertEquals(a.y(),b.y());assertEquals(a.z(),b.z());
            assertEquals(0,a.player());assertEquals(1,b.player());
        }
    }
    @Test void everyRetroActionPressesOnlyItsOwnButton(){
        int[] bits={0,8,1,9,10,11,3};
        for(int player=0;player<2;player++)for(int selected=0;selected<bits.length;selected++)
            for(int i=0;i<7;i++)assertEquals(i==selected?(i==6?-.08:-.12):0,
                    DualCabinetControls.motion(DualCabinetControls.PARTS.get(player*8+i),1<<bits[selected],false).pressY());
    }
    @Test void nesDoesNotInventFourExtraActionButtons(){
        for(int player=0;player<2;player++)for(int i=0;i<7;i++)assertEquals(i<2?-.12:i==6?-.08:0,
                DualCabinetControls.motion(DualCabinetControls.PARTS.get(player*8+i),255,true).pressY());
    }
    @Test void neutralAndInvalidMasksNeverAnimate(){
        for(var p:DualCabinetControls.PARTS)for(int mask:new int[]{0,-1})
            assertEquals(new DualCabinetControls.Motion(0,0,0),DualCabinetControls.motion(p,mask,false));
    }
    @Test void joystickUsesViewerDirectionsAndCancelsOpposingAxes(){
        var stick=DualCabinetControls.PARTS.get(7);
        assertEquals(8,DualCabinetControls.motion(stick,1<<4,false).tiltX());
        assertEquals(-8,DualCabinetControls.motion(stick,1<<5,false).tiltX());
        assertEquals(8,DualCabinetControls.motion(stick,1<<7,false).tiltZ());
        assertEquals(-8,DualCabinetControls.motion(stick,1<<6,false).tiltZ());
        assertEquals(new DualCabinetControls.Motion(0,0,0),DualCabinetControls.motion(stick,0xF0,false));
    }
    @Test void all4096RetroMasksRemainInsideBoundedUserTravel(){
        for(int mask=0;mask<4096;mask++)for(var p:DualCabinetControls.PARTS){
            var m=DualCabinetControls.motion(p,mask,false);
            assertTrue(m.pressY()>=-.12&&m.pressY()<=0);assertTrue(Math.abs(m.tiltX())<=8);assertTrue(Math.abs(m.tiltZ())<=8);
        }
    }
}
