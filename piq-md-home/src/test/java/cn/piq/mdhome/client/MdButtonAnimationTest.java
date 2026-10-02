// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MdButtonAnimationTest {
    @Test void allKeysPressAndReleaseInBoundedTime(){
        for(int bit=0;bit<12;bit++){var a=new MdButtonAnimation();var key=new Object();a.update(key,1<<bit,1);assertTrue(a.value(1<<bit)>0);a.update(key,1<<bit,25_000_001);assertEquals(1,a.value(1<<bit));a.update(key,0,60_000_001);assertEquals(.5,a.value(1<<bit),1e-9);a.update(key,0,100_000_001);assertEquals(0,a.value(1<<bit));}
    }
    @Test void identityAndLifecycleClearAllKeys(){var a=new MdButtonAnimation();var one=new Object();a.update(one,4095,1);a.update(one,4095,50_000_001);var two=new Object();a.update(two,0,70_000_001);assertFalse(a.matches(one));for(int bit=0;bit<12;bit++)assertEquals(0,a.value(1<<bit));a.update(two,4095,100_000_001);a.clear();assertFalse(a.matches(two));for(int bit=0;bit<12;bit++)assertEquals(0,a.value(1<<bit));}
    @Test void independentAnimationsAndNoNaNFromClockOrBadMask(){var a=new MdButtonAnimation();var b=new MdButtonAnimation();a.update("p1",1,100);b.update("p2",2048,100);assertEquals(0,a.value(2048));assertEquals(0,b.value(1));a.update("p1",1,1);assertTrue(a.value(1)>=0&&a.value(1)<=1);a.update("p1",-1,100);assertEquals(0,a.value(1));assertEquals(0,b.value(3));assertEquals(0,b.value(4096));}
}
