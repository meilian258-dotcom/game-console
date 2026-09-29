package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Gravity/support checks complement collision tests: a clear pipe can still float unrealistically. */
class UserTvCableGravityTest {
    private List<ArcadeDisplayStyle> styles(boolean wall) {return Arrays.stream(ArcadeDisplayStyle.values()).filter(s->UserTvLayout.supports(s)&&UserTvLayout.wall(s)==wall).toList();}
    @Test void everyTabletopTrunkStaysOnSupportForAllFcAndSuborConnectors() {
        for(var tv:styles(false))for(int type=0;type<4;type++)for(int a=0;a<4;a++)for(int b=0;b<4;b++)for(int side:new int[]{-1,1}) {
            var sockets=HomeAvCableMesh.consoleSockets(type!=0,type>=2,type==3,a);
            var bounds=HomeAvCableMesh.consoleHousing(type!=0,type>=2,type==3,a).outer();
            double dx=side*4;
            var route=HomeAvCableLayout.routeUserTv(sockets,bounds,a,tv,b,dx,0,0);
            assertFalse(route.isEmpty(),tv+" type="+type+" a="+a+" b="+b+" side="+side);
            for(int i=1;i<route.size()-1;i++)assertEquals(.019,route.get(i).y(),1e-9,"Tabletop trunk lifted off support");
            var mesh=UserTvCableMesh.buildFc(type!=0,type>=2,type==3,a,tv,b,dx,0,0);
            assertFalse(mesh.isEmpty(),tv+" rejected valid ground route "+type+"/"+a+"/"+b+"/"+side);
            for(var q:mesh)for(var p:List.of(q.a(),q.b(),q.c(),q.d()))assertTrue(p.y()>=-1e-9);
        }
    }
    @Test void wallRiseIsAtTelevisionNotAtTheConsoleForEveryFcAndSuborConnector() {
        for(var tv:styles(true))for(int type=0;type<4;type++)for(int facing=0;facing<4;facing++)for(int height:new int[]{1,2,3}) {
            Point n=HomeAvCableMesh.outward(facing);double dx=n.x()*3,dz=n.z()*3;
            var sockets=HomeAvCableMesh.consoleSockets(type!=0,type>=2,type==3,facing);
            var bounds=HomeAvCableMesh.consoleHousing(type!=0,type>=2,type==3,facing).outer();
            var route=HomeAvCableLayout.routeUserTv(sockets,bounds,facing,tv,facing,dx,height,dz);
            assertFalse(route.isEmpty());Point junction=route.get(route.size()-2);
            assertEquals(.019,route.get(1).y(),1e-9);
            assertTrue(route.stream().filter(p->Math.abs(p.y()-.019)<1e-8).count()>5);
            for(int i=1;i<route.size()-1;i++)if(route.get(i).y()>.25)
                assertTrue(Math.hypot(route.get(i).x()-junction.x(),route.get(i).z()-junction.z())<.23,"Floating rise near source");
            assertFalse(UserTvCableMesh.buildFc(type!=0,type>=2,type==3,facing,tv,facing,dx,height,dz).isEmpty(),"Actual pipe must remain drawable");
        }
    }
    @Test void wallFlushFcDoesNotUseItsOldUpwardBridgeInAnyFacing() {
        for(var tv:styles(true))for(int facing=0;facing<4;facing++)for(int height:new int[]{1,2,3}) {
            var mesh=UserTvCableMesh.buildFc(false,false,false,facing,tv,facing,0,height,0);assertFalse(mesh.isEmpty());
            var mid=UserTvLayout.socket(tv,facing,1);boolean touches=false;
            for(var q:mesh)for(var p:List.of(q.a(),q.b(),q.c(),q.d())) {
                if(p.y()<.035)touches=true;
                if(p.y()>.40)assertTrue(Math.hypot(p.x()-mid.x(),p.z()-mid.z())<.33,"Tall span must stay beside TV drop");
            }
            assertTrue(touches,"Even a close-wall lead must descend to support");
        }
    }
    @Test void sfcMultiOutSharesTabletopAndWallGravityRules() {
        for(boolean wall:new boolean[]{false,true})for(var tv:styles(wall))for(int facing=0;facing<4;facing++) {
            Point socket=HomeHardwareRenderLayout.rotate(new Point(4.325/16,1.0425/16,17.976125/16),facing);
            Point a=HomeHardwareRenderLayout.rotate(new Point(2D/16,0,3.344375/16),facing),b=HomeHardwareRenderLayout.rotate(new Point(14D/16,7.62/16,17.976125/16),facing);
            var bounds=new UserTvCableMesh.Bounds(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),7.62/16,Math.max(a.z(),b.z()));
            Point n=HomeAvCableMesh.outward(facing);double dx=n.x()*3,dz=n.z()*3;
            var mesh=UserTvCableMesh.buildMultiOut(socket,bounds,facing,.675,tv,facing,dx,wall?2:0,dz);assertFalse(mesh.isEmpty());
            var mid=UserTvLayout.socket(tv,facing,1);boolean touches=false;
            for(var q:mesh)for(var p:List.of(q.a(),q.b(),q.c(),q.d())) {
                assertTrue(p.y()>=-1e-9);if(p.y()<.035)touches=true;
                if(wall&&p.y()>.40)assertTrue(Math.hypot(p.x()-dx-mid.x(),p.z()-dz-mid.z())<.33);
            }
            assertTrue(touches,"SFC has no separate suspended routing exception");
        }
    }
    @Test void longWallRunsRemainVisibleWithinTheOriginalBoundedPointBudget() {
        for(var tv:styles(true))for(int facing=0;facing<4;facing++) {
            Point offset=HomeHardwareRenderLayout.rotate(new Point(7.5,3,.5),facing);
            double dx=offset.x()-.5,dz=offset.z()-.5;
            var mesh=UserTvCableMesh.buildFc(false,false,false,facing,tv,facing,dx,3,dz);
            assertFalse(mesh.isEmpty(),"Legal 7.616-block wide-panel connection disappeared");
            assertTrue(mesh.size()<=HomeAvCableMesh.MAX_QUADS);
        }
    }
    @Test void exportProductionCableComparisonForOfflineReview() throws Exception {
        var scenes=new LinkedHashMap<String,Object>();
        for(boolean wall:new boolean[]{false,true})for(int type=0;type<3;type++) {
            var tv=wall?ArcadeDisplayStyle.HOME_PANEL_2_WALL:ArcadeDisplayStyle.HOME_PANEL_2;
            double dx=wall?0:3,dy=wall?2:0,dz=wall?2:0;
            var mesh=type<2?UserTvCableMesh.buildFc(type==1,type==1,type==1,0,tv,0,dx,dy,dz)
                    :UserTvCableMesh.buildMultiOut(new Point(4.325/16,1.0425/16,17.976125/16),
                        new UserTvCableMesh.Bounds(2D/16,0,3.344375/16,14D/16,7.62/16,17.976125/16),0,.675,tv,0,dx,dy,dz);
            assertFalse(mesh.isEmpty());
            String name=(type==0?"fc":type==1?"subor":"sfc")+(wall?"-wall":"-table");
            scenes.put(name,Map.of("tv",wall?"panel_tv_2_wall":"panel_tv_2","source",type,"offset",new double[]{dx,dy,dz},"quads",mesh));
        }
        var direct=UserTvCableMesh.buildFc(false,false,false,0,ArcadeDisplayStyle.HOME_PANEL_2_WALL,0,0,2,0);
        scenes.put("fc-flush",Map.of("tv","panel_tv_2_wall","source",0,"offset",new double[]{0,2,0},"quads",direct));
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/cable50-preview.json"),new com.google.gson.Gson().toJson(scenes));
    }
}
