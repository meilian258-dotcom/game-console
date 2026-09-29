package cn.piq.fcarcade.layout;

import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardControlState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.retro.client.KeyboardConfig.Profile.*;
import static cn.piq.retro.client.KeyboardConfig.Preset.*;
import static cn.piq.fcarcade.layout.DualCabinetControls.InputLayout;

/** Real preset -> event state -> backend mask -> physical user-model group, not a copied bit table. */
class CabinetKeyboardAnimationTest {
    private static int press(KeyboardConfig.Profile profile,KeyboardConfig.Preset preset,int key){
        var keys=KeyboardConfig.presetKeys(profile,preset);
        var input=new KeyboardControlState(preset,keys.stream().map(k->new int[]{k}).toArray(int[][]::new));
        if(input.mode()==KeyboardControlState.Mode.FREE)input.toggle();
        input.activate(true,k->false);
        assertTrue(input.armed());assertTrue(input.key(key,1));
        int mask=input.mask(0);assertNotEquals(0,mask);
        input.key(key,0);assertEquals(0,input.mask(0));
        return mask;
    }
    private static void order(KeyboardConfig.Profile profile,KeyboardConfig.Preset preset,InputLayout layout,int...keys){
        for(int selected=0;selected<keys.length;selected++){
            int mask=press(profile,preset,keys[selected]);
            for(int player=0;player<2;player++)for(int button=0;button<7;button++){
                var part=DualCabinetControls.PARTS.get(player*8+button);
                assertEquals(button==selected?-.12:0,DualCabinetControls.motion(part,mask,layout).pressY(),
                        profile+"/"+preset+" key="+keys[selected]+" part="+part.name());
            }
        }
    }
    @Test void nativeNumpadOneToSixPressPhysicalOneToSix(){order(ARCADE,NUMPAD,InputLayout.ARCADE,321,322,323,324,325,326);}
    @Test void nativeWasdJklIopPressPhysicalOneToSix(){order(ARCADE,WASD,InputLayout.ARCADE,74,75,76,73,79,80);}
    @Test void nativeDirectionJklIopPressPhysicalOneToSix(){order(ARCADE,CLASSIC,InputLayout.ARCADE,74,75,76,73,79,80);}
    @Test void fcNumpadOneTwoPressBThenA(){order(NES,NUMPAD,InputLayout.NES,321,322);}
    @Test void fcLegacyJkPressBThenA(){order(NES,LEGACY,InputLayout.NES,74,75);}
    @Test void fcWasdJkPressBThenA(){order(NES,WASD,InputLayout.NES,74,75);}
    @Test void fcDirectionJkPressBThenA(){order(NES,CLASSIC,InputLayout.NES,74,75);}
    @Test void sfcNumpadAndWasdOrderingIsNotChangedByNativeFix(){
        order(SFC,NUMPAD,InputLayout.SFC,321,322,323,324,325,326);
        order(SFC,WASD,InputLayout.SFC,74,75,76,73,79,80);
        order(SFC,CLASSIC,InputLayout.SFC,74,75,76,73,79,80);
    }
    @Test void backendSelectionDoesNotTreatEveryNonNesCoreAsSfc(){
        assertEquals(InputLayout.NES,DualCabinetControls.layoutForBackend("piq_fc_arcade:nes"));
        assertEquals(InputLayout.SFC,DualCabinetControls.layoutForBackend("piq_sfc_home:sfc"));
        assertEquals(InputLayout.ARCADE,DualCabinetControls.layoutForBackend("piq_native_arcade:mame"));
        assertEquals(InputLayout.ARCADE,DualCabinetControls.layoutForBackend("example:future_arcade"));
    }
    @Test void userPhysicalGroupsRemainLower123Upper456ForBothPlayers(){
        for(int player=0;player<2;player++)for(int column=0;column<3;column++){
            var near=DualCabinetControls.PARTS.get(player*8+column);
            var far=DualCabinetControls.PARTS.get(player*8+column+3);
            assertEquals("p"+(player+1)+"_button_"+(column+1),near.name());
            assertEquals(near.x(),far.x());assertTrue(near.z()<far.z());
            if(column<2)assertTrue(near.x()>DualCabinetControls.PARTS.get(player*8+column+1).x());
        }
    }
    @Test void startJoystickNeutralAndReleaseRemainCommonToAllLayouts(){
        for(var layout:InputLayout.values())for(var part:DualCabinetControls.PARTS){
            assertEquals(new DualCabinetControls.Motion(0,0,0),DualCabinetControls.motion(part,0,layout));
            assertEquals(new DualCabinetControls.Motion(0,0,0),DualCabinetControls.motion(part,-1,layout));
            if(part.button()==6)assertEquals(-.08,DualCabinetControls.motion(part,1<<3,layout).pressY());
            if(part.joystick())assertEquals(8,DualCabinetControls.motion(part,1<<4,layout).tiltX());
        }
    }
}
