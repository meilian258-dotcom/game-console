package cn.piq.fcarcade.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ControllerButtonGeometryTest {
    @Test void everyOriginalFcElementKeepsItsRealButtonOrStaticBodyPartition()throws Exception {
        for(int port=0;port<2;port++) {
            var model=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/piq_fc_arcade/models/block/home_controller_p"+(port+1)+"_held.json"))).getAsJsonObject();
            int[] counts=new int[8];
            for(var value:model.getAsJsonArray("elements")) {
                var element=value.getAsJsonObject();String name=element.get("name").getAsString();
                int expected=name.contains("十字键")?1:name.contains("A黑色圆钮")?2:name.contains("B黑色圆钮")?3:name.contains("SELECT黑键")?4:name.contains("START黑键")?5:0;
                var from=element.getAsJsonArray("from");var to=element.getAsJsonArray("to");
                var vertices=new ControllerButtonGeometry.Point[8];
                for(int i=0;i<8;i++)vertices[i]=new ControllerButtonGeometry.Point((i%2==0?from:to).get(0).getAsDouble(),((i/2)%2==0?from:to).get(1).getAsDouble(),(i<4?from:to).get(2).getAsDouble());
                int actual=ControllerButtonGeometry.classify(vertices,false,port);assertEquals(expected,actual,name);counts[actual]++;
                // Actual baked faces use element rotation and float vertices, not just from/to AABBs.
                if(element.has("rotation")) {
                    var rotation=element.getAsJsonObject("rotation");var origin=rotation.getAsJsonArray("origin");
                    assertFalse(rotation.has("rescale")&&rotation.get("rescale").getAsBoolean(),"Reviewed held model has no rescale");
                    double angle=Math.toRadians(rotation.get("angle").getAsDouble()),c=Math.cos(angle),s=Math.sin(angle);
                    String axis=rotation.get("axis").getAsString();
                    for(int i=0;i<8;i++) {
                        var p=vertices[i];double x=p.x()-origin.get(0).getAsDouble(),y=p.y()-origin.get(1).getAsDouble(),z=p.z()-origin.get(2).getAsDouble();
                        double rx=x,ry=y,rz=z;
                        switch(axis) {case "x"-> {ry=c*y-s*z;rz=s*y+c*z;}case "y"->{rx=c*x+s*z;rz=-s*x+c*z;}case "z"->{rx=c*x-s*y;ry=s*x+c*y;}default->fail("Invalid rotation axis");}
                        vertices[i]=new ControllerButtonGeometry.Point(rx+origin.get(0).getAsDouble(),ry+origin.get(1).getAsDouble(),rz+origin.get(2).getAsDouble());
                    }
                }
                for(String face:element.getAsJsonObject("faces").keySet()) {
                    int[] indices=switch(face) {case "north"->new int[]{0,1,2,3};case "south"->new int[]{4,5,6,7};
                        case "west"->new int[]{0,2,4,6};case "east"->new int[]{1,3,5,7};case "down"->new int[]{0,1,4,5};case "up"->new int[]{2,3,6,7};default->throw new IllegalArgumentException(face);};
                    var quad=new ControllerButtonGeometry.Point[4];
                    for(int n=0;n<4;n++){var p=vertices[indices[n]];quad[n]=new ControllerButtonGeometry.Point((float)(p.x()/16)*16,(float)(p.y()/16)*16,(float)(p.z()/16)*16);}
                    assertEquals(expected,ControllerButtonGeometry.classify(quad,false,port),name+" / rotated face "+face);
                }
            }
            assertEquals(2,counts[1]);assertEquals(6,counts[2]);assertEquals(6,counts[3]);
            assertEquals(port==0?1:0,counts[4]);assertEquals(port==0?1:0,counts[5]);
        }
    }
    @Test void suborRealHeldTrianglesPartitionBothControllersIncludingTurboRow()throws Exception {
        var mesh=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/piq_fc_arcade/meshes/home_subor_sb926.json"))).getAsJsonObject();
        int[] previous=null;
        for(String group:new String[]{"p1_held","p2_held"}) {
            int[] counts=new int[8];
            for(var value:mesh.getAsJsonObject("groups").getAsJsonObject(group).getAsJsonArray("triangles")) {
                var p=value.getAsJsonObject().getAsJsonArray("p");var vertices=new ControllerButtonGeometry.Point[3];
                for(int i=0;i<3;i++){var v=p.get(i).getAsJsonArray();vertices[i]=new ControllerButtonGeometry.Point(v.get(0).getAsDouble(),v.get(1).getAsDouble(),v.get(2).getAsDouble());}
                counts[ControllerButtonGeometry.classify(vertices,true,0)]++;
            }
            for(int part=0;part<8;part++)assertTrue(counts[part]>0,group+" missing part "+part);
            assertEquals(36,counts[1]);assertEquals(48,counts[2]);assertEquals(48,counts[3]);
            assertEquals(12,counts[4]);assertEquals(12,counts[5]);assertEquals(48,counts[6]);assertEquals(48,counts[7]);
            if(previous!=null)assertArrayEquals(previous,counts);previous=counts;
            System.out.println(group+" real triangle partitions="+Arrays.toString(counts));
        }
    }
    @Test void rawCanonicalTransformRoundTripsBothPortsWithoutMirroringUp() {
        var point=new ControllerButtonGeometry.Point(9,7,11);
        for(int port=0;port<2;port++)assertEquals(point,ControllerButtonGeometry.raw(ControllerButtonGeometry.canonical(point,false,port),false,port));
        assertEquals(point,ControllerButtonGeometry.canonical(point,true,0));
    }
    public static void main(String[] args)throws Exception {var test=new ControllerButtonGeometryTest();int n=0;for(var m:ControllerButtonGeometryTest.class.getDeclaredMethods())if(m.isAnnotationPresent(Test.class)){m.invoke(test);n++;}System.out.println("Passed "+n+" real key-cap geometry checks.");}
}
