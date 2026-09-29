package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads only final-JAR pure geometry. No Minecraft, GLFW, Wasmtime or native core is started. */
public final class Alpha17SfcCoverProbe {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void near(double expected,double actual,String message){check(Math.abs(expected-actual)<1e-7,message);}
    public static void main(String[]args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        Path origin=Path.of(SfcCoverGeometry.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(origin.equals(expected),"production geometry must originate from the supplied final JAR");
        Path faceOrigin=Path.of(SfcCoverGeometry.Face.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(faceOrigin.equals(expected),"production Face record must originate from the supplied final JAR");
        check(Files.isRegularFile(origin)&&origin.getFileName().toString().endsWith(".jar"),"production origin is a JAR, not recompiled classes");
        var item=SfcCoverGeometry.label(false);var inserted=SfcCoverGeometry.label(true);
        near(4.88/16,item.left(),"hand label left edge");near(11.12/16,item.right(),"hand label right edge");
        near(6.43/16,item.bottom(),"hand label bottom");near(9.55/16,item.top(),"hand label top");near(7.241/16,item.z(),"hand label sits immediately ahead of original label");
        check(item.left()>4.51/16&&item.right()<11.49/16,"hand label remains inside existing shell recess");
        check(Math.abs((item.right()-item.left())/(item.top()-item.bottom())-2)<1e-6,"hand cover preserves source 2:1");
        near(5.9876/16,inserted.left(),"inserted label left edge");near(10.0124/16,inserted.right(),"inserted label right edge");
        near(3.99035/16,inserted.bottom(),"inserted label bottom");near(6.00275/16,inserted.top(),"inserted label top");near(9.910445/16,inserted.z(),"inserted label exact front offset");
        check(inserted.left()>5.74895/16&&inserted.right()<10.25105/16,"inserted cover remains in original label recess");
        check(inserted.z()<9.913025/16&&inserted.z()>9.90/16,"inserted cover neither z-fights nor visibly floats");
        check(Math.abs((inserted.right()-inserted.left())/(inserted.top()-inserted.bottom())-2)<1e-6,"inserted cover preserves source 2:1");
        near((item.left()*16-8)*.645/16+.5,inserted.left(),"inserted transform matches original card x scaling");
        near((item.bottom()*16-4.6)*.645/16+2.81/16,inserted.bottom(),"inserted transform matches original card y scaling");
        near((item.z()*16-8)*.645/16+10.4/16,inserted.z(),"inserted transform matches original card z scaling");
        String path=origin.toString().replace('\\','/').replace("\"","\\\"");
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"production_origin\":\"final-jar-only\",\"production_jar\":\""+path+"\",\"minecraft_or_native_core_started\":false}");
    }
}
