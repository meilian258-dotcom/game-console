package cn.piq.fcarcade.layout;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;

/** Bundled body elements, loaded once. Actual blank front panels must not inherit a coin-door hull. */
final class CabinetModelSolids {
    private static final List<Box> LEGACY=read("legacy_animated"),DUAL=read("user_dual"),PORTRAIT=read("portrait");
    private CabinetModelSolids(){}
    static List<Box> boxes(boolean dual,boolean compact,boolean portrait,int turns){
        var result=new ArrayList<Box>();
        for(var b:portrait?PORTRAIT:dual?DUAL:LEGACY){
            var a=transform(b.minX(),b.minY(),b.minZ(),dual,compact,portrait,turns);
            var z=transform(b.maxX(),b.maxY(),b.maxZ(),dual,compact,portrait,turns);
            result.add(new Box(Math.min(a.x(),z.x()),a.y(),Math.min(a.z(),z.z()),Math.max(a.x(),z.x()),z.y(),Math.max(a.z(),z.z())));
        }
        return List.copyOf(result);
    }
    private static Point transform(double x,double y,double z,boolean dual,boolean compact,boolean portrait,int turns){
        var p=portrait?PortraitCabinetGeometry.point(2*x,2*y,2*z):new Point((dual?.25:0)+x/16,y/16,(dual?DualCabinetGeometry.modelZOffset(compact):0)+z/16);
        return RocketArcadeGeometry.rotate(p,turns);
    }
    private static List<Box> read(String name){
        try(var in=Objects.requireNonNull(CabinetModelSolids.class.getResourceAsStream("/assets/piq_fc_arcade/models/block/"+name+"/body.json"))){
            var elements=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("elements");
            var out=new ArrayList<Box>();
            for(var e:elements){
                var row=e.getAsJsonObject();var from=row.getAsJsonArray("from");var to=row.getAsJsonArray("to");
                var rotation=row.has("rotation")?row.getAsJsonObject("rotation"):null;
                double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};
                for(int corner=0;corner<8;corner++){
                    double[] p=new double[3];for(int i=0;i<3;i++)p[i]=((corner&(1<<i))==0?from:to).get(i).getAsDouble();
                    if(rotation!=null){
                        int axis="xyz".indexOf(rotation.get("axis").getAsString());if(axis<0)throw new IllegalStateException("Axis");
                        var origin=rotation.getAsJsonArray("origin");double angle=Math.toRadians(rotation.get("angle").getAsDouble());
                        int i=(axis+1)%3,j=(axis+2)%3;double u=p[i]-origin.get(i).getAsDouble(),v=p[j]-origin.get(j).getAsDouble();
                        double scale=rotation.has("rescale")&&rotation.get("rescale").getAsBoolean()?1/Math.cos(angle):1;
                        p[i]=origin.get(i).getAsDouble()+(u*Math.cos(angle)-v*Math.sin(angle))*scale;
                        p[j]=origin.get(j).getAsDouble()+(u*Math.sin(angle)+v*Math.cos(angle))*scale;
                    }
                    for(int i=0;i<3;i++){min[i]=Math.min(min[i],p[i]);max[i]=Math.max(max[i],p[i]);}
                }
                out.add(new Box(min[0],min[1],min[2],max[0],max[1],max[2]));
            }
            return List.copyOf(out);
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
}
