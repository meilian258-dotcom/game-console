package cn.piq.sfchome.client;

import cn.piq.sfchome.layout.SfcConsoleScale;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Final-JAR-only independent model-unit equations, actual pure transforms and original mesh.
 * No renderer context, Minecraft instance, world, emulator, socket or audio device. */
public final class SfcScale12FinalProbe {
    private static int checks,vertices,triangles;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static void near(double actual,double expected,String label){check(Math.abs(actual-expected)<.000025,label+": "+actual+" != "+expected);}
    private static JsonObject resource(String name)throws Exception{
        var in=SfcConsoleScale.class.getResourceAsStream("/assets/piq_sfc_home/"+name);if(in==null)throw new AssertionError("Missing final resource "+name);
        try(var reader=new InputStreamReader(in,StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
    }
    private static double[] console(double x,double y,double z){return new double[]{8+(x-8)*1.5,y*1.5,10.66025+(z-10.66025)*1.5};}
    private static double[] independent(String group,String part,double x,double y,double z){
        if(Set.of("body","slot_cover","inserted").contains(group))return console(x,y,z);
        if(!group.equals("p1_docked")&&!group.equals("p2_docked"))return new double[]{x,y,z};
        if(part.equals("console_ports"))return console(x,y,z);
        if(part.equals("p1_cable")||part.equals("p2_cable")){
            double t=z<=3.17?0:z>=5.16?1:(z-3.17)/1.99;double anchor=group.equals("p1_docked")?10.025:5.975;
            return new double[]{x+(console(anchor,.7225,5.275)[0]-anchor)*t,y+.36125*t,z-1.25*(1-t)-2.692625*t};
        }
        return new double[]{x,y,z-1.25};
    }
    private static double[] rotate(double[] p,int turns){return switch(turns){case 1->new double[]{16-p[2],p[1],p[0]};case 2->new double[]{16-p[0],p[1],16-p[2]};case 3->new double[]{p[2],p[1],16-p[0]};default->p;};}
    private static double[][] bounds(List<double[]> points){double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};for(var p:points)for(int i=0;i<3;i++){lo[i]=Math.min(lo[i],p[i]);hi[i]=Math.max(hi[i],p[i]);}return new double[][]{lo,hi};}
    private static void exactBounds(List<double[]> points,SfcConsoleScale.Bounds expected){var b=bounds(points);near(b[0][0],expected.minX(),"bounds minX");near(b[0][1],expected.minY(),"bounds minY");near(b[0][2],expected.minZ(),"bounds minZ");near(b[1][0],expected.maxX(),"bounds maxX");near(b[1][1],expected.maxY(),"bounds maxY");near(b[1][2],expected.maxZ(),"bounds maxZ");}
    private static double[] project(double[] p,JsonObject gui){
        double[] v=new double[3],rotation={0,0,0},translation={0,0,0};for(int i=0;i<3;i++){v[i]=(p[i]-8)*(gui.has("scale")?gui.getAsJsonArray("scale").get(i).getAsDouble():1);if(gui.has("rotation"))rotation[i]=Math.toRadians(gui.getAsJsonArray("rotation").get(i).getAsDouble());if(gui.has("translation"))translation[i]=gui.getAsJsonArray("translation").get(i).getAsDouble();}
        double x=v[0],y=v[1],z=v[2],a=rotation[2];double xx=Math.cos(a)*x-Math.sin(a)*y,yy=Math.sin(a)*x+Math.cos(a)*y;x=xx;y=yy;
        a=rotation[1];xx=Math.cos(a)*x+Math.sin(a)*z;double zz=-Math.sin(a)*x+Math.cos(a)*z;x=xx;z=zz;
        a=rotation[0];yy=Math.cos(a)*y-Math.sin(a)*z;zz=Math.sin(a)*y+Math.cos(a)*z;y=yy;z=zz;
        return new double[]{x+translation[0],y+translation[1],z+translation[2]};
    }
    public static void main(String[] args)throws Exception{
        Path jar=Path.of(args[0]).toRealPath();check(jar.toString().endsWith(".jar"),"Explicit final JAR");
        for(var type:List.of(SfcConsoleScale.class,SfcConsoleScale.Point.class,SfcConsoleScale.Bounds.class,SfcHardwareMeshData.class,SfcButtonAnimation.class,SfcControllerPoseLayout.class,SfcCoverGeometry.class,SfcAvCableGeometry.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"Final production origin "+type.getName());
        near(SfcConsoleScale.SCALE,1.5,"scale");near(SfcConsoleScale.PIVOT_X,8,"pivot x");near(SfcConsoleScale.PIVOT_Z,10.66025,"pivot z");near(SfcConsoleScale.DOCK_Z,-1.25,"dock translation only");
        var data=resource("meshes/sfc_hardware.json");Map<String,List<SfcHardwareMeshData.Part>> parsed;
        try(var reader=new java.io.StringReader(data.toString())){parsed=SfcHardwareMeshData.read(reader);}
        var transformed=new LinkedHashMap<String,List<double[]>>();int unchangedHandVertices=0;
        for(var group:parsed.entrySet()){
            var points=new ArrayList<double[]>();transformed.put(group.getKey(),points);
            for(var part:group.getValue()){
                float[] input=part.vertices(),copy=input.clone();float[] output=SfcConsoleScale.vertices(group.getKey(),part.name(),input);check(Arrays.equals(input,copy),"source vertex array not mutated");check(output.length==input.length,"no topology changes");
                if(group.getKey().equals("controller")||group.getKey().equals("cartridge")){check(output==input,"standalone controller/card geometry passes through unchanged");unchangedHandVertices+=output.length/8;}
                boolean cord=part.name().equals("p1_cable")||part.name().equals("p2_cable");
                for(int i=0;i<output.length;i+=8){vertices++;double[] expected=independent(group.getKey(),part.name(),input[i]*16,input[i+1]*16,input[i+2]*16),actual={output[i]*16,output[i+1]*16,output[i+2]*16};
                    for(int axis=0;axis<3;axis++)near(actual[axis],expected[axis],"independent position equation");points.add(actual);
                    check(Float.floatToRawIntBits(input[i+3])==Float.floatToRawIntBits(output[i+3])&&Float.floatToRawIntBits(input[i+4])==Float.floatToRawIntBits(output[i+4]),"every original UV unchanged");
                    if(!cord)for(int axis=5;axis<8;axis++)check(Float.floatToRawIntBits(input[i+axis])==Float.floatToRawIntBits(output[i+axis]),"rigid/uniform transform leaves original normals");
                }
                for(int i=0;i<output.length;i+=24){triangles++;double ax=output[i+8]-output[i],ay=output[i+9]-output[i+1],az=output[i+10]-output[i+2],bx=output[i+16]-output[i],by=output[i+17]-output[i+1],bz=output[i+18]-output[i+2];double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,n=Math.sqrt(nx*nx+ny*ny+nz*nz);check(n>1e-12,"no collapsed triangle after cord reattachment");if(cord){near(output[i+5],nx/n,"cord normal x");near(output[i+6],ny/n,"cord normal y");near(output[i+7],nz/n,"cord normal z");}}
            }
        }
        check(unchangedHandVertices>1000,"standalone geometry actually visited");
        for(int turns=0;turns<4;turns++){
            final int direction=turns;exactBounds(transformed.get("body").stream().map(p->rotate(p,direction)).toList(),SfcConsoleScale.body(turns));exactBounds(transformed.get("inserted").stream().map(p->rotate(p,direction)).toList(),SfcConsoleScale.inserted(turns));
            var render=SfcConsoleScale.render(turns);
            for(String group:List.of("body","inserted","slot_cover","p1_docked","p2_docked"))for(var p:transformed.get(group)){var v=rotate(p,turns);check(v[0]>=render.minX()-.00003&&v[0]<=render.maxX()+.00003&&v[1]>=render.minY()-.00003&&v[1]<=render.maxY()+.00003&&v[2]>=render.minZ()-.00003&&v[2]<=render.maxZ()+.00003,"four-facing render bound contains all visible world vertices");}
            var socket=rotate(console(5.55,.695,15.5375),turns);var endpoint=SfcAvCableGeometry.console(turns);check(endpoint.sockets().size()==1,"one MULTI OUT, not RCA");var actual=endpoint.sockets().getFirst();near(actual.x()*16,socket[0],"AV socket x");near(actual.y()*16,socket[1],"AV socket y");near(actual.z()*16,socket[2],"AV socket z");near(endpoint.plugScale(),.675,"plug follows 1.5 console scale");
            var tv=new SfcAvCableGeometry.Endpoint(List.of(new SfcAvCableGeometry.Vec(-3.3,.22,.875),new SfcAvCableGeometry.Vec(-3.15,.22,.875),new SfcAvCableGeometry.Vec(-3,.22,.875)),new SfcAvCableGeometry.Box(-3.8,0,0,-2.7,1.2,.875),new SfcAvCableGeometry.Vec(0,0,1),0,1);
            var cable=SfcAvCableGeometry.build(endpoint,tv);check(cable.visible(),"four facing AV route remains valid: "+cable.rejection());check(cable.quads().size()<=SfcAvCableGeometry.MAX_QUADS,"AV geometry budget");
            check(cable.quads().stream().filter(q->q.part().equals("console-multi-out")).count()==5,"complete MULTI OUT rectangular plug");check(cable.quads().stream().anyMatch(q->q.part().equals("tv-plug-2")),"three TV RCA preserved");
        }
        for(int port=0;port<2;port++){
            String group=port==0?"p1_docked":"p2_docked",part=port==0?"p1_cable":"p2_cable";double anchor=port==0?10.025:5.975;
            var end=SfcConsoleScale.part(group,part,anchor,.7225,5.275);var socket=console(anchor,.7225,5.275);near(end.x(),socket[0],"cord console end x");near(end.y(),socket[1],"cord console end y");near(end.z(),socket[2],"cord console end z");
            var front=SfcConsoleScale.part(group,part,anchor,.7225,3.17);near(front.x(),anchor,"pad-end unchanged width");near(front.y(),.7225,"pad-end unchanged height");near(front.z(),1.92,"pad-end follows translation");
        }
        var label=SfcCoverGeometry.label(true);var left=console(label.left()*16,label.bottom()*16,label.z()*16);var right=console(label.right()*16,label.top()*16,label.z()*16);
        near((right[0]-left[0])/(right[1]-left[1]),2,"dynamic cover remains 2:1");near(left[2],SfcConsoleScale.inserted(0).minZ()-.003,"cover offset stays in front of inserted label");check(left[0]>4.16&&right[0]<11.84&&left[1]>3.27&&right[1]<7.62,"scaled cover remains inside card face");
        var gui=new LinkedHashMap<String,Object>();
        for(String item:List.of("cartridge","console")){
            var display=resource("models/item/"+item+".json").getAsJsonObject("display").getAsJsonObject("gui");var points=new ArrayList<double[]>();
            for(String group:item.equals("cartridge")?List.of("cartridge"):List.of("body","p1_docked","p2_docked","slot_cover"))for(var p:transformed.get(group))points.add(project(p,display));
            var b=bounds(points);check(b[0][0]>=-8&&b[1][0]<=8&&b[0][1]>=-8&&b[1][1]<=8,"all GUI vertices fit actual 16x16 icon: "+item);
            if(item.equals("cartridge")){near(display.getAsJsonArray("scale").get(0).getAsDouble(),2.4,"card GUI scale exactly 2.4");check(b[1][0]-b[0][0]>12&&b[1][1]-b[0][1]>7,"card icon is visibly enlarged, not only recentred");}
            gui.put(item,Map.of("bounds_centered_16px",b,"display",display));
        }
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"visited_vertices",vertices,"triangles",triangles,"standalone_unchanged_vertices",unchangedHandVertices,"gui",gui,"production_origin","final-jar-only","minecraft_or_core_started",false)));
    }
}
