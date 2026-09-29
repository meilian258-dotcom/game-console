package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcButtonAnimationTest {
    private SfcButtonAnimation.Binding binding(String key){return new SfcButtonAnimation.Binding(key,.5f,.5f,.5f,.07f/16);}
    @Test void everySfcBitMovesOnlyItsCorrespondingCap(){
        String[] names={"button_b","button_y","select","start","button_a","button_x","shoulder_l","shoulder_r"};
        int[] bits={0,1,2,3,8,9,10,11};
        for(int i=0;i<names.length;i++)for(int mask=0;mask<4096;mask++){
            var b=binding(names[i]);var t=SfcButtonAnimation.sample(b,mask);
            assertEquals((mask&(1<<bits[i]))==0?0:-b.press(),t.y());assertEquals(0,t.pitch());assertEquals(0,t.roll());
        }
    }
    @Test void directionalTiltsDepressTheCorrectSideWithoutChangingInput(){
        var b=binding("dpad");assertTrue(SfcButtonAnimation.sample(b,1<<4).pitch()>0);assertTrue(SfcButtonAnimation.sample(b,1<<5).pitch()<0);
        assertTrue(SfcButtonAnimation.sample(b,1<<6).roll()<0);assertTrue(SfcButtonAnimation.sample(b,1<<7).roll()>0);
        assertEquals(SfcButtonAnimation.Transform.REST,SfcButtonAnimation.sample(b,0xf0));
        assertEquals(SfcButtonAnimation.Transform.REST,SfcButtonAnimation.sample(null,0xfff));
    }
    @Test void pressAndReleaseAreImmediateAndShouldersUseIndependentTravel(){
        var l=binding("shoulder_l");var r=binding("shoulder_r");
        for(int i=0;i<1000;i++){
            assertEquals(-.07f/16,SfcButtonAnimation.sample(l,1<<10).y());assertEquals(0,SfcButtonAnimation.sample(r,1<<10).y());
            assertEquals(SfcButtonAnimation.Transform.REST,SfcButtonAnimation.sample(l,0));
            assertEquals(-.07f/16,SfcButtonAnimation.sample(r,1<<11).y());assertEquals(0,SfcButtonAnimation.sample(l,1<<11).y());
        }
    }
}
