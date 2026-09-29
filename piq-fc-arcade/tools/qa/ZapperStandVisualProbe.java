import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.block.model.ItemTransform;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Real MC ItemTransform parser + PoseStack against models loaded only from the supplied JAR. */
public final class ZapperStandVisualProbe {
    private static int assertions;
    private static void check(boolean v,String why){assertions++;if(!v)throw new AssertionError(why);}
    private static JsonObject json(ZipFile z,String p)throws Exception{return JsonParser.parseString(new String(z.getInputStream(z.getEntry("assets/piq_fc_arcade/models/"+p+".json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static Vector3f vec(JsonArray a){return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private static void gui(JsonObject model,JsonObject wrapper,String name){
        var input=wrapper.getAsJsonObject("display").getAsJsonObject("gui");
        var tr=new ItemTransform.Deserializer().deserialize(input,ItemTransform.class,null);
        var expected=vec(input.getAsJsonArray("scale"));check(tr.scale.equals(expected),name+" parser must not clamp scale");
        expected=vec(input.getAsJsonArray("translation")).mul(1f/16);check(tr.translation.distance(expected)<1e-6,name+" translation not clamped");
        var poses=new PoseStack();tr.apply(false,poses);poses.translate(-.5,-.5,-.5);
        var low=new Vector3f(Float.POSITIVE_INFINITY);var high=new Vector3f(Float.NEGATIVE_INFINITY);
        for(var elem:model.getAsJsonArray("elements")){
            var e=elem.getAsJsonObject();var a=vec(e.getAsJsonArray("from"));var b=vec(e.getAsJsonArray("to"));
            for(int i=0;i<8;i++){
                var v=new Vector3f((i&1)==0?a.x:b.x,(i&2)==0?a.y:b.y,(i&4)==0?a.z:b.z);
                if(e.has("rotation")){
                    var r=e.getAsJsonObject("rotation");check(!r.has("rescale")||!r.get("rescale").getAsBoolean(),"source has no rescale");
                    var origin=vec(r.getAsJsonArray("origin"));float radians=(float)Math.toRadians(r.get("angle").getAsFloat());
                    Quaternionf q=switch(r.get("axis").getAsString()){case "x"->new Quaternionf().rotationX(radians);case "y"->new Quaternionf().rotationY(radians);case "z"->new Quaternionf().rotationZ(radians);default->throw new AssertionError("axis");};
                    v.sub(origin).rotate(q).add(origin);
                }
                v.mul(1f/16);poses.last().pose().transformPosition(v);
                check(Float.isFinite(v.x)&&Float.isFinite(v.y)&&Float.isFinite(v.z),"finite real pose");
                check(Math.abs(v.x)<.475&&Math.abs(v.y)<.475,"entire source fits GUI slot "+name+" "+v);
                low.min(v);high.max(v);
            }
        }
        check(new Vector3f(low).add(high).mul(.5f).length()<1e-4,"actual parser/PoseStack centered "+name);
    }
    public static void main(String[] args)throws Exception{
        check(args.length==2,"production and actual MC JAR");
        check(Path.of(ItemTransform.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[1]).toRealPath()),"actual MC parser origin");
        try(var z=new ZipFile(args[0])){
            var stand=json(z,"block/zapper_stand/stand");var cable=json(z,"block/zapper_stand/cable");var plug=json(z,"block/zapper_stand/connector");
            check(stand.getAsJsonArray("elements").size()==85,"original stand count");check(cable.getAsJsonArray("elements").size()==51,"original cable count");check(plug.getAsJsonArray("elements").size()==18,"original connector count");
            var tool=json(z,"item/zapper_stand_cable");var expected=new JsonArray();for(var e:cable.getAsJsonArray("elements"))expected.add(e);for(var e:plug.getAsJsonArray("elements"))expected.add(e);
            check(tool.getAsJsonArray("elements").equals(expected),"tool reuses original cable and connector with no coordinate/UV changes");
            gui(stand,json(z,"item/zapper_stand"),"stand");gui(tool,tool,"cable");
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_minecraft_itemtransform\":true,\"actual_pose_stack\":true,\"source_geometry_preserved\":true,\"resource_origin\":\"supplied-jar-only\",\"production_compiled\":false,\"minecraft_window_started\":false}");
    }
}
