package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZapperPoseLayoutTest {
    @Test void heldOriginIsGripNotTheStandOrCoilCenter() {
        for(var v:new ZapperPoseLayout.View[]{ZapperPoseLayout.View.FIRST_LEFT,ZapperPoseLayout.View.FIRST_RIGHT,
                ZapperPoseLayout.View.THIRD_LEFT,ZapperPoseLayout.View.THIRD_RIGHT}) {
            var p=ZapperPoseLayout.item(v);assertEquals(ZapperPoseLayout.GRIP,p.origin());
            assertEquals(new Point(.5,.5,.5),ZapperPoseLayout.itemPoint(ZapperPoseLayout.GRIP,p));assertEquals(1,p.scale());
        }
    }
    @Test void sourceNegativeXBarrelPointsForwardForBothHandsAndViews() {
        for(var v:new ZapperPoseLayout.View[]{ZapperPoseLayout.View.FIRST_LEFT,ZapperPoseLayout.View.FIRST_RIGHT,
                ZapperPoseLayout.View.THIRD_LEFT,ZapperPoseLayout.View.THIRD_RIGHT}) {
            var p=ZapperPoseLayout.item(v);var g=ZapperPoseLayout.GRIP;
            var muzzle=ZapperPoseLayout.itemPoint(new Point(g.x()-8,g.y(),g.z()),p);
            assertEquals(.5,muzzle.x(),1e-12);assertEquals(.5,muzzle.y(),1e-12);assertEquals(0,muzzle.z(),1e-12);
        }
    }
    @Test void everyOuterTransformKeepsUniformOriginalProportions() {
        var p=new Point(4,5,8);var q=new Point(11,1,9);
        for(var view:ZapperPoseLayout.View.values()) {
            var pose=ZapperPoseLayout.item(view);
            assertEquals(p.distanceTo(q)*pose.scale()/16,ZapperPoseLayout.itemPoint(p,pose).distanceTo(ZapperPoseLayout.itemPoint(q,pose)),1e-12);
        }
    }
    @Test void firstPersonGunGripStaysBelowAndBesideCrosshair() {
        var right=ZapperPoseLayout.first(true,0);var left=ZapperPoseLayout.first(false,0);
        assertEquals(-left.x(),right.x());assertTrue(right.x()>.3);assertTrue(right.y()<-.3);assertTrue(right.z()<-.5);
        assertTrue(ZapperPoseLayout.first(true,1).y()<right.y());assertEquals(right,ZapperPoseLayout.first(true,Float.NaN));
    }
    @Test void armTracksHeadButNeverTouchesOtherArmState() {
        for(boolean right:new boolean[]{true,false}) {
            var pose=ZapperPoseLayout.arm(right,.3,-.2);assertEquals(-Math.PI/2+.3,pose.pitch());assertEquals(-.2,pose.yaw());
            assertEquals(right?-.1:.1,pose.roll());
        }
        assertEquals(new ZapperPoseLayout.ArmPose(0,0,0),ZapperPoseLayout.arm(true,Double.NaN,0));
    }
    @Test void triggerRotatesExactlyEightDegreesAroundOriginalLocalZPivot() {
        var p=ZapperPoseLayout.TRIGGER_PIVOT;
        assertEquals(p,ZapperPoseLayout.triggerPoint(p,1));
        var q=ZapperPoseLayout.triggerPoint(new Point(p.x()+1,p.y(),p.z()+2),1);
        assertEquals(p.x()+Math.cos(Math.toRadians(8)),q.x(),1e-12);
        assertEquals(p.y()+Math.sin(Math.toRadians(8)),q.y(),1e-12);assertEquals(p.z()+2,q.z());
        assertEquals(new Point(p.x()+1,p.y(),p.z()),ZapperPoseLayout.triggerPoint(new Point(p.x()+1,p.y(),p.z()),0));
    }
    @Test void triggerTravelIsBoundedTimeBasedAndReversible() {
        assertEquals(.5,ZapperPoseLayout.advanceTrigger(0,true,35_000_000),1e-12);
        assertEquals(1,ZapperPoseLayout.advanceTrigger(.5,true,35_000_000));
        assertEquals(.5,ZapperPoseLayout.advanceTrigger(1,false,35_000_000));
        assertEquals(0,ZapperPoseLayout.advanceTrigger(.5,false,Long.MAX_VALUE));
        assertEquals(.5,ZapperPoseLayout.advanceTrigger(.5,true,-1));
        assertEquals(0,ZapperPoseLayout.advanceTrigger(Double.NaN,false,0));
    }
}
