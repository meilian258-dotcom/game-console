import cn.piq.fcarcade.layout.CabinetVideoGeometry;
import cn.piq.fcarcade.netplay.*;
import cn.piq.nativearcade.NativeNetplayProfile;
import java.util.*;

/** Reads the final addon declaration without loading a core, Minecraft, or any user ROM/save. */
public final class ArcadePresentationWatchProbe {
    public static void main(String[] args){
        var profile=NativeNetplayProfile.profile("presentation_probe.zip");
        if(profile.jniAspect()!=NetplayProfile.JniAspect.PRESENTED||profile.ports()!=4)
            throw new AssertionError("Pinned FBNeo must explicitly declare presented DAR and four ports");
        int corners=0;
        for(int ccw=0;ccw<4;ccw++){
            int cw=(4-ccw)&3;
            float raw=profile.rawJniAspect(3f/4,cw);
            if(Math.abs(CabinetVideoGeometry.displayAspect(raw,cw)-3.0/4)>1e-6)
                throw new AssertionError("DAR changed through canonical frame boundary");
            for(float u:new float[]{0,1})for(float v:new float[]{0,1}){
                float x=u,y=v;
                for(int n=0;n<ccw;n++){float oldX=x;x=y;y=1-oldX;}
                var source=CabinetVideoGeometry.textureUv(x,y,cw);
                if(source.u()!=u||source.v()!=v)throw new AssertionError("CCW/CW corner mismatch: "+ccw);
                corners++;
            }
        }
        var readonly=new NetplayProcess.Grant(905,UUID.randomUUID(),false,false);
        try(var observer=new NetplayProcess(readonly,()->new byte[16],chunk->{},profile,Map::of,true)){
            if(observer.grant().port()!=-1||observer.grant().host()||observer.grant().player())
                throw new AssertionError("Four-port topology changed observer authority");
        }
        System.out.println("{\"ok\":true,\"rotationCorners\":"+corners+",\"rawDarContract\":true,\"fourPortReadOnlyConstructor\":true,\"nativeStarted\":false,\"minecraftStarted\":false}");
    }
}
