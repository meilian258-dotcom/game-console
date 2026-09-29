package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTvCableTangentTest {
    private static final List<ArcadeDisplayStyle> CRT=List.of(ArcadeDisplayStyle.HOME_GRAY_CRT,ArcadeDisplayStyle.HOME_RED_CRT);
    @Test void adjacentCrtOnEitherSideHasNoReturningNeckOrCrossingInAnyFacing() {
        for(var tv:CRT)for(int turns=0;turns<4;turns++)for(int side:new int[]{-1,1}) {
            Point offset=HomeHardwareRenderLayout.rotate(new Point(.5+side,.5,.5),turns);
            verify(tv,turns,turns,offset.x()-.5,offset.z()-.5,37);
        }
    }
    @Test void rotatedAndSeparatedCrtEndpointsUseTheSameNoBacktrackingRule() {
        for(var tv:CRT)for(int source=0;source<4;source++)for(int target=0;target<4;target++)for(int side:new int[]{-1,1})
            verify(tv,source,target,side*3,0,101);
    }
    @Test void seededBowsAreStableDistinctBoundedAndKeepStraightNecks() {
        var socket=HomeAvCableMesh.consoleSockets(false,false,0);var box=HomeAvCableMesh.consoleHousing(false,false,false,0).outer();
        var tv=ArcadeDisplayStyle.HOME_GRAY_CRT;
        var regular=HomeAvCableLayout.routeUserTv(socket,box,0,tv,0,4,0,0,null,42,false);
        var variants=new HashSet<List<Point>>();int changed=0;
        for(long seed=0;seed<12;seed++) {
            var route=HomeAvCableLayout.routeUserTv(socket,box,0,tv,0,4,0,0,null,seed,true);
            assertFalse(route.isEmpty());assertEquals(route,HomeAvCableLayout.routeUserTv(socket,box,0,tv,0,4,0,0,null,seed,true));
            assertEquals(regular.size(),route.size());assertTrue(route.size()<=HomeAvCableLayout.MAX_ROUTE_POINTS);
            assertEquals(regular.get(1),route.get(1));assertEquals(regular.get(2),route.get(2));
            assertEquals(regular.get(regular.size()-3),route.get(route.size()-3));
            for(int i=1;i<route.size()-1;i++) {
                assertEquals(.019,route.get(i).y(),1e-12);
                assertTrue(distance(regular.get(i),route.get(i))<=.120000001);
            }
            if(!regular.equals(route))changed++;variants.add(route);
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,tv,0,4,0,0,seed);assertFalse(mesh.isEmpty());
            assertEquals(mesh,UserTvCableMesh.buildFc(false,false,false,0,tv,0,4,0,0,seed));
        }
        assertTrue(changed>=10,"Long clear runs must not silently fall back to all-straight");
        assertTrue(variants.size()>=8,"Independent connections should have stable varied bows");
    }
    @Test void legacySharedSignaturesRemainDeterministicForSfc36() {
        var tv=ArcadeDisplayStyle.HOME_RED_CRT;var socket=new Point(4.325/16,1.0425/16,17.976125/16);
        var bounds=new UserTvCableMesh.Bounds(2D/16,0,3.344375/16,14D/16,7.62/16,17.976125/16);
        var a=UserTvCableMesh.buildMultiOut(socket,bounds,0,.675,tv,0,3,0,0);
        assertFalse(a.isEmpty());assertEquals(a,UserTvCableMesh.buildMultiOut(socket,bounds,0,.675,tv,0,3,0,0));
    }
    @Test void rendererCachesByConnectionIdentityAndDoesNotRandomizePerFrame() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/cn/piq/fcarcade/client/HomeHardwareRenderer.java"));
        String draw=source.substring(source.indexOf("private void drawCable("),source.indexOf("private static void drawCartridge("));
        assertTrue(draw.contains("java.util.UUID linkId = console.linkId()"));
        assertTrue(draw.contains("linkId.getMostSignificantBits() ^ linkId.getLeastSignificantBits()"));
        assertTrue(draw.contains("!java.util.Objects.equals(cached.linkId(), linkId)"));
        assertTrue(draw.contains("tv.getZ()-origin.getZ(),cableSeed)"));
        assertTrue(draw.contains("new CachedCable(tv.immutable(), consoleState, tvState, linkId, route)"));
        assertFalse(draw.contains("new Random"));assertFalse(draw.contains("Math.random"));
        assertFalse(draw.contains("RandomSource"));assertFalse(draw.contains("System.nanoTime"));
    }
    @Test void exportRealCrtMeshForOfflineReview() throws Exception {
        var scenes=new LinkedHashMap<String,Object>();
        for(var tv:CRT)for(int side:new int[]{-1,1})for(int turns:new int[]{0,1}) {
            double gap=turns==0?1:3;
            var mesh=UserTvCableMesh.buildFc(false,false,false,0,tv,turns,side*gap,0,0,341);
            assertFalse(mesh.isEmpty());
            scenes.put(tv.name().toLowerCase()+"-"+side+"-"+turns,Map.of("tv",tv==CRT.getFirst()?"gray_crt_tv":"red_crt_tv",
                    "source",0,"turns",turns,"offset",new double[]{side*gap,0,0},"quads",mesh));
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/cable59-preview.json"),new com.google.gson.Gson().toJson(scenes));
    }
    private static void verify(ArcadeDisplayStyle tv,int source,int target,double dx,double dz,long seed) {
        var sockets=HomeAvCableMesh.consoleSockets(false,false,source);var box=HomeAvCableMesh.consoleHousing(false,false,false,source).outer();
        var route=HomeAvCableLayout.routeUserTv(sockets,box,source,tv,target,dx,0,dz,null,seed,true);
        String name=tv+" source="+source+" target="+target+" offset="+dx+","+dz;
        assertFalse(route.isEmpty(),name);var first=HomeAvCableMesh.outward(source);var last=HomeAvCableMesh.outward(target);
        assertTrue(dot(sub(route.get(2),route.get(1)),first)>=-1e-9,name+" source reversed");
        assertTrue(dot(sub(route.get(route.size()-3),route.get(route.size()-2)),last)>=-1e-9,name+" television reversed");
        var mesh=UserTvCableMesh.buildFc(false,false,false,source,tv,target,dx,0,dz,seed);assertFalse(mesh.isEmpty(),name);
        var tubes=tubes(mesh);assertTrue(tubes.size()>=6);
        // Production mesh: trunk, four short coax barrel/rib tubes, flexible source lead.
        var trunk=tubes.get(0);var lead=tubes.get(5);
        assertTrue(dot(HomeAvCableMesh.unit(sub(lead.getLast(),lead.get(lead.size()-2))),
                HomeAvCableMesh.unit(sub(trunk.get(1),trunk.getFirst())))>.999,name+" discontinuous neck");
        for(int i=1;i<lead.size();i++)assertTrue(dot(sub(lead.get(i),lead.get(i-1)),first)>=-1e-9,name+" coiled source branch");
        var combined=new ArrayList<>(lead);combined.addAll(trunk.subList(1,trunk.size()));
        for(int i=1;i<combined.size();i++)for(int j=i+2;j<combined.size();j++) {
            Point a=combined.get(i-1),b=combined.get(i),c=combined.get(j-1),d=combined.get(j);
            if(Math.max(a.y(),b.y())<Math.min(c.y(),d.y())-.015||Math.max(c.y(),d.y())<Math.min(a.y(),b.y())-.015)continue;
            assertFalse(cross(a,b,c)*cross(a,b,d)<-1e-10&&cross(c,d,a)*cross(c,d,b)<-1e-10,name+" crossed cable centerline");
        }
    }
    private static List<List<Point>> tubes(List<HomeAvCableMesh.Quad> mesh) {
        var result=new ArrayList<List<Point>>();int at=0,sides=HomeAvCableMesh.SIDES;
        while(at<mesh.size()) {
            var path=new ArrayList<Point>();
            while(at<mesh.size()&&!mesh.get(at).a().equals(mesh.get(at).d())) {
                double ax=0,ay=0,az=0,bx=0,by=0,bz=0;
                for(int i=0;i<sides;i++){var q=mesh.get(at+i);ax+=q.a().x()/sides;ay+=q.a().y()/sides;az+=q.a().z()/sides;bx+=q.d().x()/sides;by+=q.d().y()/sides;bz+=q.d().z()/sides;}
                if(path.isEmpty())path.add(new Point(ax,ay,az));path.add(new Point(bx,by,bz));at+=sides;
            }
            if(path.isEmpty())throw new AssertionError("Expected a production tube");
            result.add(path);at+=sides*2;
        }
        return result;
    }
    private static Point sub(Point a,Point b){return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    private static double dot(Point a,Point b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    private static double distance(Point a,Point b){var d=sub(a,b);return Math.sqrt(dot(d,d));}
    private static double cross(Point a,Point b,Point c){return(b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());}
}
