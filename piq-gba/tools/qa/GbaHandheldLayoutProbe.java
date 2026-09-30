package cn.piq.gba.client;

import java.util.*;

/** Actual production layout and input-to-motion checks, plus exact numbers for offline previews. */
public final class GbaHandheldLayoutProbe {
    static int assertions;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static void near(double a,double b){check(Math.abs(a-b)<1e-8,"Expected "+a+" ~= "+b);}
    static double distance(GbaHandheldLayout.Point a,GbaHandheldLayout.Point b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    static List<Double> point(GbaHandheldLayout.Point p){return List.of(p.x(),p.y(),p.z());}
    static List<Double> pose(GbaHandheldLayout.Pose p){return List.of(p.x(),p.y(),p.z(),p.yaw(),p.pitch(),p.roll(),p.scale());}
    static String json(Object value){
        if(value instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey().toString())+":"+json(e.getValue())).toList())+"}";
        if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(GbaHandheldLayoutProbe::json).toList())+"]";
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";return String.valueOf(value);
    }
    public static void main(String[] args){
        var tl=GbaHandheldLayout.screen(0);var bl=GbaHandheldLayout.screen(1);var br=GbaHandheldLayout.screen(2);var tr=GbaHandheldLayout.screen(3);
        near(distance(tl,tr)/distance(tl,bl),1.5);near(tl.y(),1.302);check(tl.z()<bl.z()&&tl.x()<tr.x(),"Source top -Z and right +X");
        var poses=new LinkedHashMap<String,Object>();
        for(var view:GbaHandheldLayout.View.values()){
            var p=GbaHandheldLayout.item(view);poses.put(view.name(),pose(p));
            var a=GbaHandheldLayout.point(tl,p);var b=GbaHandheldLayout.point(bl,p);var c=GbaHandheldLayout.point(tr,p);
            near(distance(a,c)/distance(a,b),1.5);check(c.x()>a.x(),"No horizontal mirror: "+view);
            if(view!=GbaHandheldLayout.View.GROUND)check(a.y()>b.y(),"Screen top stays up: "+view);
        }
        for(int i=0;i<=100;i++)for(boolean right:new boolean[]{false,true})for(boolean two:new boolean[]{false,true}){
            var item=GbaHandheldLayout.item(GbaHandheldLayout.View.FIRST);var rig=GbaHandheldLayout.first(right,two,0,i/100.0);
            for(int corner=0;corner<4;corner++){
                var p=GbaHandheldLayout.point(GbaHandheldLayout.screen(corner),item);p=GbaHandheldLayout.transform(new GbaHandheldLayout.Point(p.x()-.5,p.y()-.5,p.z()-.5),rig);
                check(p.z()<-.8&&p.y()<-.15,"Actual first screen stays in front and below crosshair");
                check(Math.abs(p.y()/p.z())<Math.tan(Math.toRadians(35)),"Vertical 70-degree view bounds");
                check(Math.abs(p.x()/p.z())<Math.tan(Math.toRadians(35))*4/3,"4:3 minimum view bounds");
            }
        }
        var keys=Map.of("button_a",8,"button_b",0,"button_select",2,"button_start",3,"shoulder_l",10,"shoulder_r",11);
        for(boolean right:new boolean[]{false,true})for(boolean two:new boolean[]{false,true}){
            var rig=GbaHandheldLayout.first(right,two,0,0,true);
            var low=GbaHandheldLayout.first(right,two,0,0,false);
            check(rig.scale()>low.scale()*1.8,"Raised screen materially enlarged");
            var points=new ArrayList<GbaHandheldLayout.Point>();
            for(int corner=0;corner<4;corner++){
                var p=GbaHandheldLayout.point(GbaHandheldLayout.screen(corner),GbaHandheldLayout.item(GbaHandheldLayout.View.FIRST));
                p=GbaHandheldLayout.transform(new GbaHandheldLayout.Point(p.x()-.5,p.y()-.5,p.z()-.5),rig);points.add(p);
                check(p.z()<-.2,"Raised display in front of camera");
                check(Math.abs(p.y()/p.z())<Math.tan(Math.toRadians(35)),"Raised display fits vertical 70-degree bounds");
                check(Math.abs(p.x()/p.z())<Math.tan(Math.toRadians(35))*4/3,"Raised display fits 4:3 view");
            }
            near(distance(points.get(0),points.get(3))/distance(points.get(0),points.get(1)),1.5);
            check(points.get(3).x()>points.get(0).x()&&points.get(0).y()>points.get(1).y(),"Raised orientation correct");
        }
        for(var e:keys.entrySet())for(int bit=0;bit<12;bit++){
            var move=GbaHandheldLayout.motion(e.getKey(),1<<bit);check((move.y()<0)==(bit==e.getValue()),"Exact input-to-model button "+e.getKey()+" bit"+bit);
            near(move.pitch(),0);near(move.roll(),0);
        }
        check(GbaHandheldLayout.motion("dpad",1<<4).pitch()<0,"Up presses source -Z");check(GbaHandheldLayout.motion("dpad",1<<5).pitch()>0,"Down presses source +Z");
        check(GbaHandheldLayout.motion("dpad",1<<6).roll()>0,"Left presses source -X");check(GbaHandheldLayout.motion("dpad",1<<7).roll()<0,"Right presses source +X");
        for(String key:keys.keySet())near(GbaHandheldLayout.motion(key,0).y(),0);near(GbaHandheldLayout.motion("dpad",0).y(),0);
        check(GbaHandheldLayout.eligible(true,true,false,false,false,false,false),"Normal eligible");
        for(int reason=0;reason<7;reason++){boolean[] values={true,true,false,false,false,false,false};values[reason]=!values[reason];check(!GbaHandheldLayout.eligible(values[0],values[1],values[2],values[3],values[4],values[5],values[6]),"Pose eligibility guard "+reason);}
        for(double equip:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-100,100}){var pose=GbaHandheldLayout.first(true,true,equip,Double.NaN);check(Double.isFinite(pose.y())&&Double.isFinite(pose.pitch()),"Finite/clamped equip/swing");}
        var arms=new LinkedHashMap<String,Object>();for(boolean right:new boolean[]{false,true}){var a=GbaHandheldLayout.arm(right);arms.put(right?"right":"left",List.of(a.x(),a.y(),a.z(),a.pitch(),a.roll(),a.scale()));}
        System.out.println(json(Map.of("ok",true,"assertions",assertions,"poses",poses,"arms",arms,"first_two",pose(GbaHandheldLayout.first(true,true,0,0)),"first_single",pose(GbaHandheldLayout.first(true,false,0,0)),"screen",List.of(point(tl),point(bl),point(br),point(tr)),"center",List.of(GbaHandheldLayout.CENTER_X,GbaHandheldLayout.CENTER_Y,GbaHandheldLayout.CENTER_Z))));
    }
}
