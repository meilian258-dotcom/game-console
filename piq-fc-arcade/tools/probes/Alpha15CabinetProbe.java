package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.client.cabinet.CabinetKeys;
import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Compiled against the final JAR only. Never compiles or shadows production classes. */
public final class Alpha15CabinetProbe {
    private static int assertions;
    private static void check(boolean ok){assertions++;if(!ok)throw new AssertionError("Packaged cabinet check "+assertions);}
    private static void near(double a,double b){check(Math.abs(a-b)<1e-9);}
    private static void rejects(Runnable action){
        try{action.run();throw new AssertionError("Expected validation rejection");}
        catch(IllegalArgumentException|UnsupportedOperationException expected){assertions++;}
    }
    private static void origin(Class<?> type,Path jar)throws Exception {
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));
    }
    private static Point minus(Point a,Point b){return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z());}
    private static double dot(Point a,Point b){return a.x()*b.x()+a.y()*b.y()+a.z()*b.z();}
    public static void main(String[] args)throws Exception {
        Path jar=Path.of(args[0]).toRealPath();
        origin(CabinetLeaseLedger.class,jar);origin(CabinetKeys.class,jar);origin(CabinetVideoGeometry.class,jar);origin(CabinetFrame.class,jar);
        var ledger=new CabinetLeaseLedger<String>();UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        var first=ledger.acquire(owner,"overworld:1,2,3#old","sfc",0);check(first!=null);
        check(ledger.acquire(other,first.target(),"mame",1)==null);check(ledger.acquire(owner,"another","mame",1)==null);
        check(!ledger.heartbeat(other,first.id(),10));check(ledger.release(other,first.id())==null);
        check(ledger.heartbeat(owner,first.id(),79));check(ledger.get(first.id()).expires()==159);
        check(!ledger.heartbeat(owner,first.id(),159));check(ledger.release(owner,first.id())!=null);
        var next=ledger.acquire(owner,first.target(),"mame",160);check(!next.id().equals(first.id()));
        check(ledger.release(owner,first.id())==null);check(!ledger.heartbeat(owner,first.id(),161));
        check(ledger.get(next.id())!=null);ledger.release(owner,next.id());
        for(int i=0;i<CabinetLeaseLedger.MAX_LEASES;i++)check(ledger.acquire(UUID.randomUUID(),"p"+i,"sfc",200)!=null);
        check(ledger.acquire(UUID.randomUUID(),"overflow","sfc",200)==null);rejects(()->ledger.all().clear());
        int[][] keys={{74,85,259,257,265,264,263,262,75,73,79,80},{70,82,53,50,87,83,65,68,71,84,89,72}};
        var input=new CabinetKeys();check(!input.press(-1));
        for(int port=0;port<2;port++)for(int bit=0;bit<12;bit++){
            input.clear();check(input.press(keys[port][bit]));check(!input.press(keys[port][bit]));
            check((port==0?input.player1():input.player2())==(1<<bit));check((port==0?input.player2():input.player1())==0);
            check(input.release(keys[port][bit]));check(input.player1()==0&&input.player2()==0);
        }
        for(var row:keys)for(int key:row)input.press(key);check(input.player1()==4095&&input.player2()==4095);
        input.clear();check(input.player1()==0&&input.player2()==0);
        int scenarios=0;double[] aspects={.25,.5,.75,1,8.0/7,4.0/3,16.0/9,2,4,32};
        for(boolean dual:new boolean[]{false,true})for(int facing=0;facing<4;facing++)for(int rotation=-5;rotation<=8;rotation++)for(double raw:aspects){
            scenarios++;var frame=CabinetVideoGeometry.frame(dual,facing,raw,rotation);var q=frame.image();var glass=frame.glass();
            double display=(Math.floorMod(rotation,4)&1)==0?raw:1/raw;
            near(q.aspectRatio(),display);near(frame.displayAspect(),display);near(glass.aspectRatio(),dual?16.0/9:4.0/3);
            near(q.center().x(),glass.center().x());near(q.center().y(),glass.center().y());near(q.center().z(),glass.center().z());
            check(q.width()<=glass.width()+1e-9&&q.height()<=glass.height()+1e-9);
            check(Math.abs(q.width()-glass.width())<1e-9||Math.abs(q.height()-glass.height())<1e-9);
            near(q.normal().y(),Math.sin(Math.PI/8));near(dot(q.normal(),q.normal()),1);
            Set<String> uvs=new HashSet<>();var across=minus(glass.lowerMaxX(),glass.lowerMinX());var up=minus(glass.upperMinX(),glass.lowerMinX());
            for(var vertex:frame.vertices()){
                uvs.add(vertex.u()+":"+vertex.v());var delta=minus(vertex.point(),glass.lowerMinX());
                double x=dot(delta,across)/dot(across,across),y=dot(delta,up)/dot(up,up);
                check(x>=-1e-9&&x<=1+1e-9&&y>=-1e-9&&y<=1+1e-9);near(dot(delta,q.normal()),0);
            }
            check(uvs.equals(Set.of("0.0:0.0","0.0:1.0","1.0:0.0","1.0:1.0")));
            check(frame==CabinetVideoGeometry.frame(dual,facing,raw,rotation));
        }
        float[][][] expected={{{0,1},{1,1},{1,0},{0,0}},{{0,0},{0,1},{1,1},{1,0}},
                {{1,0},{0,0},{0,1},{1,1}},{{1,1},{1,0},{0,0},{0,1}}};
        for(int rotation=0;rotation<4;rotation++){
            var frame=CabinetVideoGeometry.frame(true,0,4.0/3,rotation);
            for(int i=0;i<4;i++){near(frame.vertices().get(i).u(),expected[rotation][i][0]);near(frame.vertices().get(i).v(),expected[rotation][i][1]);}
        }
        rejects(()->CabinetVideoGeometry.frame(false,0,Double.NaN,0));rejects(()->CabinetVideoGeometry.frame(false,0,1e-100,0));
        rejects(()->new CabinetFrame(2,2,new int[3],4F/3,0,new short[0]));
        rejects(()->new CabinetFrame(1,1,new int[1],4F/3,4,new short[0]));
        rejects(()->new CabinetFrame(1,1,new int[1],4F/3,0,new short[3]));
        check(new CabinetFrame(1,1,new int[1],4F/3,1,new short[0]).rotation()==1);
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"geometry_scenarios\":"+scenarios
                +",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
