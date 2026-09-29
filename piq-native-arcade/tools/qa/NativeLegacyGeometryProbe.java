package cn.piq.nativearcade.layout;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Compare old actual FC21+Native7 in an isolated classloader, never a hand-recreated fixture. */
public final class NativeLegacyGeometryProbe {
    private static int checks,tests;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static List<Long> bits(Object value)throws Exception{
        if(value instanceof Double d)return List.of(Double.doubleToLongBits(d));
        if(value instanceof Float f)return List.of((long)Float.floatToIntBits(f));
        var result=new ArrayList<Long>();
        for(var component:value.getClass().getRecordComponents())result.addAll(bits(component.getAccessor().invoke(value)));
        return result;
    }
    public static void main(String[] args)throws Exception{
        var oldFc=Path.of(args[0]).toRealPath();var oldNative=Path.of(args[1]).toRealPath();var expected=Path.of(args[2]).toRealPath();String mode=args[3];
        check(Path.of(NativeCabinetLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"new layout explicit production origin");
        try(var loader=new URLClassLoader(new java.net.URL[]{oldNative.toUri().toURL(),oldFc.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            var old=Class.forName("cn.piq.nativearcade.layout.NativeCabinetLayout",true,loader);
            check(Path.of(old.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(oldNative),"actual old Native7 origin");
            var oldGeometry=Class.forName("cn.piq.fcarcade.layout.DualCabinetGeometry",false,loader);
            check(Path.of(oldGeometry.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(oldFc),"actual old FC21 geometry origin");
            for(String constant:List.of("MODEL_SCALE","MODEL_Y_OFFSET"))check(bits(old.getField(constant).get(null)).equals(bits(NativeCabinetLayout.class.getField(constant).get(null))),"old literal unchanged "+constant);
            var turns=new ArrayList<Integer>();for(int t=-16;t<20;t++)turns.add(t);turns.add(Integer.MIN_VALUE);turns.add(Integer.MAX_VALUE);
            for(int turn:turns){
                for(String method:List.of("screen","bounds","occupancy")){
                    var before=old.getMethod(method,int.class).invoke(null,turn);var after=NativeCabinetLayout.class.getMethod(method,int.class).invoke(null,turn);
                    check(bits(before).equals(bits(after)),"all original coordinate double bits: "+method+" / "+turn);
                }
                check(NativeCabinetLayout.screen(turn)==NativeCabinetLayout.screen(Math.floorMod(turn,4)),"immutable four-facing cache");
                for(double ratio:new double[]{.1,.75,1,4.0/3,16.0/9,2.5,10}){
                    var before=old.getMethod("frame",int.class,double.class).invoke(null,turn,ratio);
                    check(bits(before).equals(bits(NativeCabinetLayout.frame(turn,ratio))),"uncropped old aspect-fit exact bits "+turn+" / "+ratio);
                }
            }
        }
        for(var type:List.of(NativeCabinetLayoutTest.class,NativeVideoIntegrationTest.class))
            for(var method:type.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){
                try{method.invoke(type.getDeclaredConstructor().newInstance());}catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError(method.getName(),failure.getCause());}tests++;
            }
        check(tests==12,"six geometry and six video tests preserved");
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"junit_tests\":"+tests+",\"exact_original_double_bits\":true,\"production_origin\":\""+mode+"\",\"minecraft_or_core_started\":false}");
    }
}
