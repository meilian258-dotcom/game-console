package cn.piq.fcarcade.client;

import cn.piq.fcarcade.home.UserTvLayout;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTvCableMeshTest {
    @Test void exportActualCableGeometryForOfflineVisualReview() throws Exception {
        var scenes=new java.util.LinkedHashMap<String,Object>();
        for(String name:List.of("gray_crt_tv","panel_tv_2","panel_tv_2_wall","direct_wall")) {
            var style=name.equals("gray_crt_tv")?ArcadeDisplayStyle.HOME_GRAY_CRT:name.equals("panel_tv_2")?ArcadeDisplayStyle.HOME_PANEL_2:ArcadeDisplayStyle.HOME_PANEL_2_WALL;
            boolean direct=name.equals("direct_wall");double dx=direct?0:3,dy=UserTvLayout.wall(style)?1:0;
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,style,0,dx,dy,0);
            assertFalse(mesh.isEmpty(),name);verify(mesh,style,0,dx,dy,0);
            scenes.put(name,java.util.Map.of("tv",direct?"panel_tv_2_wall":name,"offset",new double[]{dx,dy,0},"quads",mesh));
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/cable49-preview.json"),new com.google.gson.Gson().toJson(scenes));
    }
    @Test void wallFlushFcDirectlyBelowScreenHasAVisibleWallClearCable() {
        for(var style:styles())if(UserTvLayout.wall(style))for(int turns=0;turns<4;turns++)for(double dy:new double[]{1,2,3}) {
            var mesh=UserTvCableMesh.buildFc(false,false,false,turns,style,turns,0,dy,0);
            assertFalse(mesh.isEmpty(),style+" direct below facing="+turns+" height="+dy);
            verify(mesh,style,turns,0,dy,0);
            var b=UserTvLayout.bounds(style,0);
            assertTrue(HomeAvCableMesh.clearOf(mesh,0,HomeAvCableMesh.orientedBox(new HomeAvCableMesh.Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,13.51/16),turns,new Point(0,dy,0))));
        }
    }
    private static List<ArcadeDisplayStyle> styles(){return java.util.Arrays.stream(ArcadeDisplayStyle.values()).filter(UserTvLayout::supports).toList();}
    @Test void fcAndAllSuborLayoutsConnectToAllNewTelevisionsInEveryFacing() {
        int count=0;
        for(var style:styles())for(int type=0;type<4;type++)for(int source=0;source<4;source++)for(int tv=0;tv<4;tv++) {
            Point n=HomeAvCableMesh.outward(tv);double dx=n.x()*4,dz=n.z()*4;
            var mesh=UserTvCableMesh.buildFc(type!=0,type>=2,type==3,source,style,tv,dx,0,dz);
            assertFalse(mesh.isEmpty(),style+" source="+type+"/"+source+" target="+tv);
            verify(mesh,style,tv,dx,0,dz);count++;
        }
        assertEquals(384,count);
    }
    @Test void scaledSfcMultiOutAlsoUsesAllSixNewTelevisionSockets() {
        for(var style:styles())for(int source=0;source<4;source++)for(int tv=0;tv<4;tv++) {
            // Exact SfcConsoleScale values: source socket is the original MULTI OUT, not FC coax.
            Point socket=HomeHardwareRenderLayout.rotate(new Point(4.325/16,1.0425/16,17.976125/16),source);
            Point a=HomeHardwareRenderLayout.rotate(new Point(2D/16,0,3.344375/16),source),b=HomeHardwareRenderLayout.rotate(new Point(14D/16,7.62/16,17.976125/16),source);
            var bounds=new UserTvCableMesh.Bounds(Math.min(a.x(),b.x()),0,Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),7.62/16,Math.max(a.z(),b.z()));
            Point n=HomeAvCableMesh.outward(tv);double dx=n.x()*4,dz=n.z()*4;
            var mesh=UserTvCableMesh.buildMultiOut(socket,bounds,source,.675,style,tv,dx,0,dz);
            assertFalse(mesh.isEmpty(),style+" source="+source+" target="+tv);verify(mesh,style,tv,dx,0,dz);
        }
    }
    @Test void allTelevisionEndsStillHaveThreeDifferentAvBarrels() {
        for(var style:styles()) {
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,style,0,0,0,4);
            for(int color:new int[]{HomeAvCableMesh.YELLOW,HomeAvCableMesh.WHITE,HomeAvCableMesh.RED})
                assertTrue(mesh.stream().anyMatch(q->q.color()==color),style+" missing RCA channel");
            var sockets=UserTvCableMesh.tvSockets(style,0,new Point(0,0,4));
            for(int c=0;c<3;c++){var p=UserTvLayout.socket(style,0,c);assertEquals(new Point(p.x(),p.y(),p.z()+4),sockets[c]);}
        }
    }
    @Test void wallBehindTheConsoleAndInvalidCoordinatesFailClosed() {
        assertTrue(UserTvCableMesh.buildFc(false,false,false,0,ArcadeDisplayStyle.HOME_PANEL_2_WALL,0,4,0,-4).isEmpty());
        assertTrue(UserTvCableMesh.buildFc(false,false,false,0,ArcadeDisplayStyle.HOME_GRAY_CRT,0,Double.NaN,0,4).isEmpty());
        assertTrue(UserTvCableMesh.buildFc(false,false,false,0,ArcadeDisplayStyle.HOME_GRAY_CRT,0,0,0,9).isEmpty());
    }
    @Test void raisedAndLoweredWallPanelsKeepActualPipeFacesClearOfBodyBracketAndWall() {
        for(var style:styles())if(UserTvLayout.wall(style))for(int turns=0;turns<4;turns++)for(double dy:new double[]{-1,1,2}) {
            Point n=HomeAvCableMesh.outward(turns);double dx=n.x()*4,dz=n.z()*4;
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,style,turns,dx,dy,dz);
            assertFalse(mesh.isEmpty(),style+"/"+turns+" height="+dy);verify(mesh,style,turns,dx,dy,dz);
            var b=UserTvLayout.bounds(style,0);Point offset=new Point(dx,dy,dz);
            var body=HomeAvCableMesh.orientedBox(new HomeAvCableMesh.Box(b.minX()/16,b.minY()/16,b.minZ()/16,b.maxX()/16,b.maxY()/16,13.51/16),turns,offset);
            var bracket=HomeAvCableMesh.orientedBox(new HomeAvCableMesh.Box(11.6/16,7.02/16,13.34/16,(UserTvLayout.width(style)*16-11.6)/16,8.68/16,1),turns,offset);
            assertTrue(HomeAvCableMesh.clearOf(mesh,0,body));assertTrue(HomeAvCableMesh.clearOf(mesh,0,bracket));
        }
    }
    private static void verify(List<HomeAvCableMesh.Quad> mesh,ArcadeDisplayStyle style,int turns,double dx,double dy,double dz) {
        assertTrue(mesh.size()<HomeAvCableMesh.MAX_QUADS);
        for(var q:mesh)for(Point p:List.of(q.a(),q.b(),q.c(),q.d())) {
            assertTrue(Double.isFinite(p.x()+p.y()+p.z()));assertTrue(p.y()>=Math.min(0,dy)-1e-9);
            if(UserTvLayout.wall(style)) {
                Point local=HomeHardwareRenderLayout.rotate(new Point(p.x()-dx,p.y()-dy,p.z()-dz),-turns);
                assertTrue(local.z()<1-1e-8,"Actual pipe surface crossed the supporting wall");
            }
        }
    }
    public static void main(String[] args)throws Exception {
        int count=0;var test=new UserTvCableMeshTest();
        for(var method:UserTvCableMeshTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.invoke(test);count++;}
        System.out.println("Passed "+count+" new-TV cable checks (480 separated FC/Subor/SFC facing cases).");
    }
}
