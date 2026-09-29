package cn.piq.fcarcade.layout;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.*;

/** Bundled model-derived hulls; no client resource manager or world dependencies. */
public final class CabinetOutlineGeometry {
    private static final double[][] DUAL=read("dual"),PORTRAIT=read("portrait");
    private CabinetOutlineGeometry(){}
    private static double[][] read(String name){
        try(var in=Objects.requireNonNull(CabinetOutlineGeometry.class.getResourceAsStream("/assets/piq_fc_arcade/layout/"+name+"_outline.json"))){
            var data=new Gson().fromJson(new InputStreamReader(in,StandardCharsets.UTF_8),double[][].class);
            if(data.length>24)throw new IllegalStateException("Excess cabinet outline bands");
            return data;
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static List<Box> boxes(boolean portrait,boolean compact,int turns){
        var out=new ArrayList<Box>();
        for(var b:portrait?PORTRAIT:DUAL){
            var a=portrait?PortraitCabinetGeometry.point(b[0],b[1],b[2]):new Point(.25+b[0]/16,b[1]/16,DualCabinetGeometry.modelZOffset(compact)+b[2]/16);
            var z=portrait?PortraitCabinetGeometry.point(b[3],b[4],b[5]):new Point(.25+b[3]/16,b[4]/16,DualCabinetGeometry.modelZOffset(compact)+b[5]/16);
            a=RocketArcadeGeometry.rotate(a,turns);z=RocketArcadeGeometry.rotate(z,turns);
            out.add(new Box(Math.min(a.x(),z.x()),a.y(),Math.min(a.z(),z.z()),Math.max(a.x(),z.x()),z.y(),Math.max(a.z(),z.z())));
        }
        return List.copyOf(out);
    }
}
