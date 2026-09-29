package cn.piq.fcarcade.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Independent actual-Minecraft VertexConsumer probe; production classes are never replaced. */
public final class HomeVideoAspectProbe {
    private static int checks, cases;
    private static Constructor<?> legacy, fitted;
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName("cn.piq.fcarcade.client.CrtScanlineVertexConsumer");
        legacy=type.getDeclaredConstructor(VertexConsumer.class,Matrix4f.class,int.class,int.class);
        legacy.setAccessible(true);
        MessageDigest fingerprint=MessageDigest.getInstance("SHA-256");
        int baselineCases=0;
        for(int turn=0;turn<4;turn++) for(float tilt:new float[]{0,.28F})
            for(double available:new double[]{1,4.0/3,16.0/9}) for(int viewport:new int[]{0,120,1080}) {
                Matrix4f pose=pose(turn,tilt);
                Capture captured=draw(pose,available,0,true,viewport,true);
                fingerprint.update(ByteBuffer.allocate(4).putInt(captured.values.size()).array());
                for(float[] v:captured.values)for(float f:v)fingerprint.update(ByteBuffer.allocate(4).putInt(Float.floatToRawIntBits(f)).array());
                baselineCases++;
            }
        System.out.println("LEGACY_CASES="+baselineCases);
        System.out.println("LEGACY_SHA256="+HexFormat.of().formatHex(fingerprint.digest()));
        if(args.length>0&&args[0].equals("--legacy-only"))return;
        fitted=type.getDeclaredConstructor(VertexConsumer.class,Matrix4f.class,int.class,int.class,double.class,boolean.class);
        fitted.setAccessible(true);
        for(int turn=0;turn<4;turn++)for(float tilt:new float[]{0,.28F})
            for(double available:new double[]{1,4.0/3,16.0/9}) {
                Matrix4f pose=pose(turn,tilt);
                for(double picture:new double[]{1,4.0/3,16.0/9}) {
                    Capture original=original(pose,available), result=draw(pose,available,picture,false,1080,false);
                    eq(4,result.values.size(),0);cases++;
                    double h=1.5,w=h*available;
                    double fittedW=picture<available?h*picture:w;
                    double fittedH=picture<available?h:w/picture;
                    for(int i=0;i<4;i++) {
                        float[] source=original.values.get(i),actual=result.values.get(i);
                        Vector3f expected=pose.transformPosition(new Vector3f((float)((i==0||i==3?-.5:.5)*fittedW),
                            (float)((i<2?-.5:.5)*fittedH),0));
                        eq(expected.x,actual[0],3e-6);eq(expected.y,actual[1],3e-6);eq(expected.z,actual[2],3e-6);
                        for(int a=3;a<16;a++)eq(source[a],actual[a],0);
                    }
                    // Full, uncropped UV corners and centered geometry, not a stretched image.
                    eq(0,result.values.get(0)[7],0);eq(1,result.values.get(1)[7],0);
                    eq(1,result.values.get(0)[8],0);eq(0,result.values.get(3)[8],0);
                    for(int axis=0;axis<3;axis++) {
                        double before=0,after=0;for(int i=0;i<4;i++){before+=original.values.get(i)[axis];after+=result.values.get(i)[axis];}
                        eq(before/4,after/4,3e-6);
                    }
                    eq(picture,distance(result.values.get(0),result.values.get(1))/distance(result.values.get(0),result.values.get(3)),5e-6);
                    truth(distance(result.values.get(0),result.values.get(1))<=w+3e-6);
                    truth(distance(result.values.get(0),result.values.get(3))<=h+3e-6);
                }
                for(double noFit:new double[]{0,-1,Double.NaN}) {
                    Capture a=draw(pose,available,0,true,1080,true),b=draw(pose,available,noFit,true,1080,false);
                    cases++;eq(a.values.size(),b.values.size(),0);
                    for(int i=0;i<a.values.size();i++)for(int c=0;c<16;c++)raw(a.values.get(i)[c],b.values.get(i)[c]);
                }
            }
        // Flat square fitted inside a wide physical screen: verify actual striped surface, not just centerline.
        Capture striped=draw(new Matrix4f(),16.0/9,1,true,1080,false);cases++;
        eq(960,striped.values.size(),0);
        double minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,minV=1,maxV=0;
        for(float[] v:striped.values) {
            eq(0,v[2],0);eq(173,v[6],0);eq(5,v[9],0);eq(6,v[10],0);eq(240,v[11],0);eq(240,v[12],0);
            truth(v[7]>=0&&v[7]<=1&&v[8]>=0&&v[8]<=1);
            minX=Math.min(minX,v[0]);maxX=Math.max(maxX,v[0]);minV=Math.min(minV,v[8]);maxV=Math.max(maxV,v[8]);
        }
        eq(-.75,minX,3e-6);eq(.75,maxX,3e-6);eq(0,minV,0);eq(1,maxV,0);
        System.out.println("ASPECT_CASES="+cases);System.out.println("ASSERTIONS="+checks);
    }
    private static Matrix4f pose(int turn,float tilt){return new Matrix4f().translate(.21F,-.13F,-2).rotateY((float)(turn*Math.PI/2)).rotateX(tilt);}
    private static Capture draw(Matrix4f pose,double available,double aspect,boolean lines,int height,boolean old)throws Exception{
        Capture out=new Capture();VertexConsumer consumer=(VertexConsumer)(old?legacy.newInstance(out,new Matrix4f(),1920,height):fitted.newInstance(out,new Matrix4f(),1920,height,aspect,lines));
        feed(consumer,pose,available);return out;
    }
    private static Capture original(Matrix4f pose,double available){Capture out=new Capture();feed(out,pose,available);return out;}
    private static void feed(VertexConsumer consumer,Matrix4f pose,double available){
        Vector3f normal=pose.transformDirection(new Vector3f(0,0,1));
        for(int i=0;i<4;i++)consumer.addVertex(pose,(float)((i==0||i==3?-.75:.75)*available),i<2?-.75F:.75F,0)
            .setColor(255,211,123,173).setUv(i==0||i==3?0:1,i<2?1:0).setUv1(5,6).setUv2(240,240).setNormal(normal.x,normal.y,normal.z);
    }
    private static double distance(float[]a,float[]b){return Math.sqrt(Math.pow(a[0]-b[0],2)+Math.pow(a[1]-b[1],2)+Math.pow(a[2]-b[2],2));}
    private static void eq(double expected,double actual,double tolerance){checks++;if(!Double.isFinite(actual)||Math.abs(expected-actual)>tolerance)throw new AssertionError(expected+" != "+actual);}
    private static void truth(boolean value){checks++;if(!value)throw new AssertionError("false");}
    private static void raw(float a,float b){checks++;if(Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError("float bytes differ");}
    private static final class Capture implements VertexConsumer{
        final List<float[]>values=new ArrayList<>();float[]v;
        public VertexConsumer addVertex(float x,float y,float z){v=new float[16];values.add(v);v[0]=x;v[1]=y;v[2]=z;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){v[3]=r;v[4]=g;v[5]=b;v[6]=a;return this;}
        public VertexConsumer setUv(float u,float w){v[7]=u;v[8]=w;return this;}
        public VertexConsumer setUv1(int u,int w){v[9]=u;v[10]=w;return this;}
        public VertexConsumer setUv2(int u,int w){v[11]=u;v[12]=w;return this;}
        public VertexConsumer setNormal(float x,float y,float z){v[13]=x;v[14]=y;v[15]=z;return this;}
    }
}
