package cn.piq.sfchome.client;
import cn.piq.sfchome.layout.SfcConsoleScale;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcConsoleScaleTest {
    private Map<String,List<SfcHardwareMeshData.Part>> mesh()throws Exception{
        try(var reader=Files.newBufferedReader(Path.of("src/main/resources/assets/piq_sfc_home/meshes/sfc_hardware.json"))){return SfcHardwareMeshData.read(reader);}
    }
    private double[] bounds(List<SfcHardwareMeshData.Part> parts,String group){
        double[] out={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var p:parts){float[] v=SfcConsoleScale.vertices(group,p.name(),p.vertices());for(int i=0;i<v.length;i+=8)for(int a=0;a<3;a++){out[a]=Math.min(out[a],v[i+a]*16);out[a+3]=Math.max(out[a+3],v[i+a]*16);}}
        return out;
    }
    @Test void exactUserBodyAndInsertedGeometryUseSameUniformScale()throws Exception{
        var m=mesh();assertArrayEquals(new double[]{2,0,3.344375,14,4.4475,17.976125},bounds(m.get("body"),"body"),.000004);
        assertArrayEquals(new double[]{4.16,3.27,11.634125,11.84,7.62,12.838625},bounds(m.get("inserted"),"inserted"),.000004);
        assertEquals(1.5,SfcConsoleScale.SCALE);assertEquals(10.66025,SfcConsoleScale.PIVOT_Z);
    }
    @Test void heldControllerAndStandaloneCardGeometryAreUntouched()throws Exception{
        var m=mesh();for(String group:List.of("controller","cartridge"))for(var p:m.get(group))assertSame(p.vertices(),SfcConsoleScale.vertices(group,p.name(),p.vertices()));
    }
    @Test void DockedControllerKeepsDimensionsAndEveryButtonOffset()throws Exception{
        for(var group:mesh().entrySet())if(group.getKey().endsWith("_docked"))for(var part:group.getValue()){
            if(part.name().equals("console_ports")||part.name().endsWith("_cable"))continue;
            var out=SfcConsoleScale.vertices(group.getKey(),part.name(),part.vertices());var in=part.vertices();
            for(int i=0;i<in.length;i+=8){assertEquals(in[i],out[i]);assertEquals(in[i+1],out[i+1]);assertEquals(in[i+2]-1.25/16,out[i+2],1e-7);for(int a=3;a<8;a++)assertEquals(in[i+a],out[i+a]);}
        }
    }
    @Test void cordsAreNonDegenerateHaveUnitNormalsAndPreserveUv()throws Exception{
        for(var group:mesh().entrySet())if(group.getKey().endsWith("_docked"))for(var p:group.getValue())if(p.name().endsWith("_cable")){
            var out=SfcConsoleScale.vertices(group.getKey(),p.name(),p.vertices());
            for(int i=0;i<out.length;i+=8){for(int a=0;a<8;a++)assertTrue(Float.isFinite(out[i+a]));assertEquals(p.vertices()[i+3],out[i+3]);assertEquals(p.vertices()[i+4],out[i+4]);assertEquals(1,out[i+5]*out[i+5]+out[i+6]*out[i+6]+out[i+7]*out[i+7],.00001);assertTrue(out[i+1]>=0);}
            // z Jacobian is positive: the free cord cannot fold or invert under the end translation.
            double prior=-100;for(int step=0;step<=1000;step++){double z=3+step*.0025;var point=SfcConsoleScale.part(group.getKey(),p.name(),10,.04,z);assertTrue(point.z()>prior);prior=point.z();}
        }
    }
    @Test void plugsAndCordEndsMeetScaledSocketsWithoutChangingPadSize(){
        for(int port=0;port<2;port++){
            String group=port==0?"p1_docked":"p2_docked",part=port==0?"p1_cable":"p2_cable";double x=port==0?10.025:5.975;
            assertEquals(SfcConsoleScale.console(x,.7225,5.275),SfcConsoleScale.part(group,part,x,.7225,5.275));
            var start=SfcConsoleScale.part(group,part,port==0?11.5:4.5,.45,3.0675);assertEquals(3.0675-1.25,start.z(),1e-9);assertEquals(.45,start.y());
        }
    }
    @Test void fourDirectionsConservativelyContainAllActualWorldVertices()throws Exception{
        var m=mesh();for(int turns=0;turns<4;turns++){
            var box=SfcConsoleScale.render(turns);
            for(String group:List.of("body","p1_docked","p2_docked","inserted"))for(var part:m.get(group)){
                var out=SfcConsoleScale.vertices(group,part.name(),part.vertices());
                for(int i=0;i<out.length;i+=8){var p=SfcConsoleScale.rotate(out[i]*16,out[i+1]*16,out[i+2]*16,turns);
                    assertTrue(p.x()>=box.minX()-1e-5&&p.x()<=box.maxX()+1e-5);assertTrue(p.y()>=box.minY()-1e-5&&p.y()<=box.maxY()+1e-5);assertTrue(p.z()>=box.minZ()-1e-5&&p.z()<=box.maxZ()+1e-5);
                }
            }
        }
    }
    @Test void parserStillRejectsAnySourceVertexBeyondSixteen()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHardwareMeshData.java"));assertTrue(source.contains("number(position,axis,0,16)/16"));
    }
    @Test void controllerShapeClearsMainBodyAndOnlyRearHousingOverhangs(){
        assertTrue(SfcConsoleScale.pad(0,0).maxZ()<SfcConsoleScale.body(0).minZ());
        assertEquals(1.976125,SfcConsoleScale.body(0).maxZ()-16,1e-9);
        assertEquals(7.62,SfcConsoleScale.inserted(0).maxY());
    }
}
