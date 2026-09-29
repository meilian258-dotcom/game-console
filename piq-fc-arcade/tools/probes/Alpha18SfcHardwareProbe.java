package cn.piq.sfchome.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Compile this probe alone against final jars. Never compiles production or starts Minecraft/core. */
public final class Alpha18SfcHardwareProbe {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message+" (#"+assertions+")");}
    private static void near(double a,double b,String message){check(Math.abs(a-b)<.000002,message);}
    private static void origin(Class<?> type,Path expected)throws Exception{check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"Final-JAR origin: "+type.getName());}
    private static double[] bounds(List<SfcHardwareMeshData.Part> parts){
        double[] b={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var p:parts)for(int i=0;i<p.vertices().length;i+=8)for(int j=0;j<3;j++){double v=p.vertices()[i+j]*16;b[j]=Math.min(b[j],v);b[j+3]=Math.max(b[j+3],v);}
        return b;
    }
    private static SfcHardwareMeshData.Part part(List<SfcHardwareMeshData.Part> parts,String name){var found=parts.stream().filter(p->p.name().equals(name)).toList();check(found.size()==1,"Exactly one part: "+name);return found.getFirst();}
    private static void cover(List<SfcHardwareMeshData.Part> parts,boolean inserted){
        var f=SfcCoverGeometry.label(inserted);double[] b=bounds(List.of(part(parts,(inserted?"inserted ":"")+"plain SFC label")));
        check(f.left()*16>b[0]&&f.right()*16<b[3],"Dynamic cover fits actual mesh recess horizontally");
        near(f.bottom()*16,b[1],"Dynamic cover matches actual label bottom");near(f.top()*16,b[4],"Dynamic cover matches actual label top");
        check(f.z()*16<b[2]&&b[2]-f.z()*16<.012,"Dynamic cover just ahead of actual new mesh, not floating/intersecting");
        near((f.right()-f.left())/(f.top()-f.bottom()),2,"2:1 dynamic cover");
    }
    private static void reject(JsonObject root)throws Exception{
        boolean rejected=false;try{SfcHardwareMeshData.read(new StringReader(root.toString()));}catch(Exception expected){rejected=true;}check(rejected,"Malformed mesh rejected by final production parser");
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected final SFC jar path");Path jar=Path.of(args[0]).toRealPath();
        check(Files.isRegularFile(jar)&&jar.getFileName().toString().endsWith(".jar"),"Production input is final jar");
        for(Class<?> type:List.of(SfcHardwareMeshData.class,SfcHardwareMeshData.Part.class,SfcControllerPoseLayout.class,SfcControllerPoseLayout.Rig.class,SfcControllerPoseLayout.Arm.class,SfcCoverGeometry.class,SfcCoverGeometry.Face.class))origin(type,jar);
        String entry="assets/piq_sfc_home/meshes/sfc_hardware.json";byte[] raw;
        try(JarFile archive=new JarFile(jar.toFile())){var item=archive.getJarEntry(entry);check(item!=null&&item.getSize()>0&&item.getSize()<=SfcHardwareMeshData.MAX_CHARS,"Bounded actual JAR mesh entry");try(var in=archive.getInputStream(item)){raw=in.readNBytes(SfcHardwareMeshData.MAX_CHARS+1);check(raw.length<=SfcHardwareMeshData.MAX_CHARS,"Bounded bytes read");}}
        var groups=SfcHardwareMeshData.read(new StringReader(new String(raw,StandardCharsets.UTF_8)));
        check(groups.keySet().equals(Set.of("body","p1_docked","p2_docked","slot_cover","inserted","controller","cartridge")),"Exact independent live mesh layers");
        int triangles=0;
        for(var group:groups.entrySet()){
            check(!group.getValue().isEmpty()&&group.getValue().size()<=SfcHardwareMeshData.MAX_PARTS,"Part budget");int count=0;
            for(var p:group.getValue()){
                check(!p.name().isBlank()&&p.name().length()<=120,"Bounded named geometry");check(p.texture().matches("(?:minecraft|piq_fc_arcade):textures/block/[a-z0-9_/]+\\.png"),"Only original texture namespaces");
                float[] v=p.vertices();check(v.length>0&&v.length%24==0,"Prepared triangles");count+=v.length/24;
                for(int i=0;i<v.length;i+=8){for(int j=0;j<8;j++)check(Float.isFinite(v[i+j]),"Finite actual prepared vertex");for(int j=0;j<3;j++)check(v[i+j]>=0&&v[i+j]<=1,"One-cell native geometry");check(v[i+3]>=0&&v[i+3]<=1&&v[i+4]>=0&&v[i+4]<=1,"Actual UV bounds");check(Math.abs(v[i+5]*v[i+5]+v[i+6]*v[i+6]+v[i+7]*v[i+7]-1)<.0001,"Unit actual normal");}
            }
            check(count>0&&count<=SfcHardwareMeshData.MAX_TRIANGLES,"Per-layer triangle budget");triangles+=count;
        }
        double[] c=bounds(groups.get("controller"));near(c[0],1.85,"Continuous controller width left");near(c[3],14.15,"Continuous controller width right");
        part(groups.get("controller"),"continuous upper grip shell");part(groups.get("controller"),"continuous lower grip shell");
        String[] names={"X","Y","A","B"};String[] textures={"blue_concrete","lime_concrete","red_concrete","yellow_concrete"};double[][] centers={{4.65,8.82},{5.62,7.85},{3.68,7.85},{4.65,6.88}};
        for(int i=0;i<4;i++){var p=part(groups.get("controller"),names[i]+" color button");check(p.texture().endsWith("/"+textures[i]+".png"),"Correct original Japanese button color");double[] b=bounds(List.of(p));near((b[0]+b[3])/2,centers[i][0],"Actual button X center");near((b[2]+b[5])/2,centers[i][1],"Actual button Z center");near(b[4],8.54,"Raised colored cap");}
        for(String name:List.of("SELECT","START")){var p=part(groups.get("controller"),name+" diagonal rubber key");double sx=0,sz=0,sxz=0;int n=0;float[] v=p.vertices();for(int i=0;i<v.length;i+=8){sx+=v[i];sz+=v[i+2];sxz+=v[i]*v[i+2];n++;}check(sxz/n-(sx/n)*(sz/n)<0,"Actual SELECT/START lean / in front view");}
        part(groups.get("controller"),"L shoulder");part(groups.get("controller"),"R shoulder");
        for(String layer:List.of("body","p1_docked","p2_docked","inserted")){double[] b=bounds(groups.get(layer));check(b[0]>=2.4&&b[3]<=13.6&&b[1]>=.09&&b[4]<=6.84&&b[2]>=.59&&b[5]<=15.203,"Preserved conservative placed housing "+layer);}
        for(var p:groups.get("inserted"))for(int i=0;i<p.vertices().length;i+=8){float[] v=p.vertices();if(v[i+1]*16<3.049)check(v[i]*16>4.64&&v[i]*16<11.36&&v[i+2]*16>9.90&&v[i+2]*16<10.90&&v[i+1]*16>2.778,"Inserted mesh inside actual clear slot");}
        cover(groups.get("cartridge"),false);cover(groups.get("inserted"),true);
        for(int bits=0;bits<128;bits++){boolean ctrl=(bits&1)!=0,empty=(bits&2)!=0,alive=(bits&4)!=0,invisible=(bits&8)!=0,scoping=(bits&16)!=0,swimming=(bits&32)!=0,flying=(bits&64)!=0;check(SfcControllerPoseLayout.eligible(ctrl,empty,alive,invisible,scoping,swimming,flying)==(ctrl&&empty&&alive&&!invisible&&!scoping&&!swimming&&!flying),"Actual first-person eligibility");}
        var right=SfcControllerPoseLayout.arm(true);var left=SfcControllerPoseLayout.arm(false);near(left.x(),-right.x(),"Symmetric hands x");near(left.y(),right.y(),"Symmetric hands y");near(left.z(),right.z(),"Symmetric hands z");near(left.roll(),-right.roll(),"Symmetric wrists");check(right.scale()>.9&&right.scale()<1.1,"Visible vanilla-sized hands");
        for(int s=0;s<=100;s++){
            var rig=SfcControllerPoseLayout.rig(0,s/100.);check(Double.isFinite(rig.y())&&Double.isFinite(rig.z())&&Double.isFinite(rig.pitch()),"Finite final-JAR pose");
            double angle=Math.toRadians(rig.pitch()+90),cos=Math.cos(angle),sin=Math.sin(angle),size=SfcControllerPoseLayout.CONTROLLER_SCALE;
            for(var p:groups.get("controller")){float[] v=p.vertices();for(int i=0;i<v.length;i+=24){double x=-(v[i]-.5)*size,cy=(v[i+1]-.5)*size,cz=-(v[i+2]-.5)*size,y=rig.y()+cos*cy-sin*cz,z=rig.z()+sin*cy+cos*cz;double tangent=Math.tan(Math.toRadians(60)/2),ndcX=x/(-z*tangent*(4./3)),ndcY=y/(-z*tangent);check(z<0&&Math.abs(ndcX)<1&&Math.abs(ndcY)<1,"Actual mesh first-person projection remains visible");}}
        }
        check(SfcControllerPoseLayout.rig(-1,-1).equals(SfcControllerPoseLayout.rig(0,0)),"Clamped equip/swing minimum");check(SfcControllerPoseLayout.rig(2,2).equals(SfcControllerPoseLayout.rig(1,1)),"Clamped equip/swing maximum");
        JsonObject root=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();var bad=root.deepCopy();bad.addProperty("version",2);reject(bad);bad=root.deepCopy();bad.getAsJsonObject("groups").remove("inserted");reject(bad);bad=root.deepCopy();bad.getAsJsonObject("materials").addProperty("shell","https://invalid.example/bitmap.png");reject(bad);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).toUpperCase();String path=jar.toString().replace('\\','/').replace("\"","\\\"");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"production_jar\":\""+path+"\",\"mesh_sha256\":\""+sha+"\",\"triangles_all_layers\":"+triangles+",\"minecraft_or_native_core_started\":false}");
    }
}
