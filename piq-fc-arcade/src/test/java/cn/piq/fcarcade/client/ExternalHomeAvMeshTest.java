package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalHomeAvMeshTest {
    static Point socket(int t){return HomeHardwareRenderLayout.rotate(new Point(6.45/16,1.23/16,15.321/16),t);}
    static UserTvCableMesh.Bounds bounds(int t){
        var a=HomeHardwareRenderLayout.rotate(new Point(3.4/16,0,6.5/16),t);var b=HomeHardwareRenderLayout.rotate(new Point(12.6/16,6.5/16,15.321/16),t);
        return new UserTvCableMesh.Bounds(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),6.5/16,Math.max(a.z(),b.z()));
    }
    @Test void smallMdRearPortConnectsAllNewTvStylesAndSixteenFacingPairs(){
        for(var style:ArcadeDisplayStyle.values())if(UserTvLayout.supports(style))for(int source=0;source<4;source++)for(int tv=0;tv<4;tv++){
            var n=HomeAvCableMesh.outward(tv);var mesh=UserTvCableMesh.buildMultiOut(socket(source),bounds(source),source,.30,style,tv,n.x()*4,0,n.z()*4);
            verify(mesh,style+" "+source+"/"+tv,0);
        }
    }
    @Test void legacyTvsAndDifferentHeightsHaveVisibleFiniteThreeChannelMeshes(){
        for(int kind=0;kind<6;kind++)for(int source=0;source<4;source++)for(int tv=0;tv<4;tv++)for(double dy:new double[]{0,1,-1}){
            var n=HomeAvCableMesh.outward(tv);
            var mesh=UserTvCableMesh.buildMultiOutLegacy(socket(source),bounds(source),source,.30,kind==1,kind>=2&&kind<=4,kind==3,kind==4,kind==5,tv,n.x()*4,dy,n.z()*4);
            verify(mesh,"legacy="+kind+" "+source+"/"+tv+" dy="+dy,dy);
        }
    }
    @Test void invalidOffsetsDoNotProduceDecorativeDisconnectedWires(){
        for(double x:new double[]{9,Double.NaN,Double.POSITIVE_INFINITY})assertTrue(UserTvCableMesh.buildMultiOutLegacy(socket(0),bounds(0),0,.3,false,false,false,false,false,0,x,0,0).isEmpty());
    }
    static void verify(List<HomeAvCableMesh.Quad> mesh,String context,double dy){
        assertFalse(mesh.isEmpty(),context);assertTrue(mesh.size()<=HomeAvCableMesh.MAX_QUADS,context);
        for(int color:new int[]{HomeAvCableMesh.RED,HomeAvCableMesh.WHITE,HomeAvCableMesh.YELLOW})assertTrue(mesh.stream().anyMatch(q->q.color()==color),context);
        for(var q:mesh)for(var p:List.of(q.a(),q.b(),q.c(),q.d())){assertTrue(Double.isFinite(p.x()+p.y()+p.z()),context);assertTrue(p.y()>=Math.min(0,dy)-1e-9,context);}
    }
}
