package cn.piq.replaycompat.capture;

import cn.piq.replaycompat.mixin.CaptureIrisBufferSource;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static cn.piq.replaycompat.capture.CaptureAssertions.*;

/** Executes the real pinned Iris override with identity-only materials and a no-GPU builder.
 * This is a headless adapter-path test, not a Mixin-loader or Minecraft rendering test. */
public final class IrisBufferCaptureTest {
    private static final String IRIS=IrisBufferContract.TARGET;
    private static final String SEGMENT="net.irisshaders.batchedentityrendering.impl.SegmentedBufferBuilder";
    private static final String VC="com/mojang/blaze3d/vertex/VertexConsumer";
    private static final String RT="net/minecraft/client/renderer/RenderType";
    private static Method wrap,flush;
    private static Object adapter;
    private static Class<?> fixtureBuilder;
    public static void main(String[] args)throws Exception {
        if(args.length==0) {
            try{new HeadlessLoader().loadClass(IrisBufferCaptureTest.class.getName()).getMethod("main",String[].class).invoke(null,(Object)new String[]{"worker"});}
            catch(InvocationTargetException failure){throw unchecked(failure.getCause());}
            return;
        }
        try(var in=IrisBufferCaptureTest.class.getClassLoader().getResourceAsStream(IRIS.replace('.','/')+".class")) {
            if(in==null){System.out.println("IrisBufferCaptureTest: Iris absent; optional runtime cases not validated");return;}
            var node=new ClassNode();new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            assertTrue(IrisBufferContract.supported(node),"unreviewed Iris fingerprint: "+IrisBufferContract.fingerprint(node));
            // An API-compatible override whose behavior changes must not silently pass the gate.
            node.methods.stream().filter(m->m.name.equals("getBuffer")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
            assertFalse(IrisBufferContract.supported(node),"modified Iris implementation must be explicitly reviewed");
        }
        setupAdapter();
        var source=RenderType.gui();var changed=RenderType.guiOverlay();
        var sink=new CapturedVerticesTest.Sink();
        Object iris=iris(sink,type->changed);
        begin(source);
        // Old mixin target is bypassed: virtual dispatch reaches Iris, never BufferSource.
        VertexConsumer old=invokeIris(iris,source);
        assertTrue(old==sink);assertEquals(0,pending().size());
        VertexConsumer actual=callWrap(source,arguments->invokeIris(iris,(RenderType)arguments[0]));
        assertTrue(actual instanceof CapturedVertices,"Iris's reassigned RenderType must not defeat material lookup");
        assertTrue(fixtureBuilder.getField("lastType").get(null)==changed,"real Iris wrapper function must execute");
        for(int i=0;i<4;i++)actual.addVertex(i,2,3).setColor(17,34,51,255).setUv(i/3f,.5f);
        var vertices=((CapturedVertices)actual).finish();assertEquals(4,vertices.size());assertEquals(4,sink.vertices);
        assertEquals(1,pending().size());
        // Empty geometry exercises queue draining without texture manager or native access.
        begin(source);callWrap(source,arguments->invokeIris(iris,(RenderType)arguments[0]));
        int[] flushes={0};flush.invoke(adapter,source,(Operation<Void>)arguments->{flushes[0]++;return null;});
        assertEquals(0,pending().size());assertEquals(1,flushes[0]);
        flush.invoke(adapter,source,(Operation<Void>)arguments->{flushes[0]++;return null;});
        assertEquals(0,pending().size());assertEquals(2,flushes[0]);

        begin(source,changed);
        Object nestedIris=iris(sink,type->source);
        callWrap(source,arguments->{
            VertexConsumer nested=callWrap(changed,inner->invokeIris(nestedIris,(RenderType)inner[0]));
            assertTrue(nested instanceof CapturedVertices);
            return invokeIris(iris,(RenderType)arguments[0]);
        });
        assertEquals(2,pending().size());
        assertTrue(pendingTexture(0).getPath().equals("test/1"));
        assertTrue(pendingTexture(1).getPath().equals("test/0"));

        begin(source);
        var sentinel=new IllegalStateException("expected failure");
        Object broken=iris(sink,type->{throw sentinel;});
        try{callWrap(source,arguments->invokeIris(broken,(RenderType)arguments[0]));throw new AssertionError("failure was swallowed");}
        catch(IllegalStateException expected){assertTrue(expected==sentinel);}
        assertEquals(0,pending().size());
        assertTrue(callWrap(source,arguments->invokeIris(iris,(RenderType)arguments[0])) instanceof CapturedVertices);
        assertEquals(1,pending().size());
        CaptureHooks.reset();
        assertTrue(callWrap(source,arguments->invokeIris(iris,(RenderType)arguments[0]))==sink,"not recording preserves original consumer");
        System.out.println("IrisBufferCaptureTest: real Iris override execution; bypass, reassigned material, vertex tee, flush, nested calls, exception recovery, idle and changed-bytecode rejection passed");
    }

    private static VertexConsumer callWrap(RenderType type,Operation<VertexConsumer> operation) {
        try{return (VertexConsumer)wrap.invoke(adapter,type,operation);}
        catch(InvocationTargetException failure){throw unchecked(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static VertexConsumer invokeIris(Object iris,RenderType type) {
        try{return (VertexConsumer)iris.getClass().getMethod("getBuffer",RenderType.class).invoke(iris,type);}
        catch(InvocationTargetException failure){throw unchecked(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static RuntimeException unchecked(Throwable failure) {
        if(failure instanceof RuntimeException runtime)return runtime;
        if(failure instanceof Error error)throw error;
        return new RuntimeException(failure);
    }
    private static void begin(RenderType... types)throws Exception {
        CaptureHooks.reset();field(CaptureHooks.class,"world").setBoolean(null,true);
        field(CaptureHooks.class,"dimension").set(null,"minecraft:overworld");
        for(int i=0;i<types.length;i++)CaptureHooks.material(ResourceLocation.fromNamespaceAndPath("piq_replay_compat","test/"+i),types[i]);
    }
    private static List<?> pending()throws Exception{return (List<?>)field(CaptureHooks.class,"PENDING").get(null);}
    private static ResourceLocation pendingTexture(int index)throws Exception {
        Object pending=pending().get(index);Method texture=pending.getClass().getDeclaredMethod("texture");texture.setAccessible(true);
        return (ResourceLocation)texture.invoke(pending);
    }
    private static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}

    private static Object iris(VertexConsumer sink,Function<RenderType,RenderType> wrapping)throws Exception {
        // Avoid ByteBufferBuilder/native allocation, not the real override or its field operations.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=field(unsafeType,"theUnsafe");Object unsafe=singleton.get(null);
        Class<?> type=Class.forName(IRIS);Object instance=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,type);
        Object builder=fixtureBuilder.getConstructor().newInstance();fixtureBuilder.getField("sink").set(null,sink);
        Class<?> segment=Class.forName(SEGMENT);Object builders=Array.newInstance(segment,1);Array.set(builders,0,builder);
        set(instance,"builders",builders);set(instance,"affinities",new LinkedHashMap<>());
        set(instance,"typeToSegment",new HashMap<>());set(instance,"renderOrder",new ArrayList<>());set(instance,"wrappingFunction",wrapping);
        Class<?> order=Class.forName("net.irisshaders.batchedentityrendering.impl.ordering.RenderOrderManager");
        set(instance,"renderOrderManager",Proxy.newProxyInstance(order.getClassLoader(),new Class<?>[]{order},(proxy,method,args)->null));
        return instance;
    }
    private static void setupAdapter()throws Exception {
        var loader=new FixtureLoader();String adapterName="cn/piq/replaycompat/capture/ConcreteIrisAdapter";
        var writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,adapterName,null,Type.getInternalName(CaptureIrisBufferSource.class),null);
        constructor(writer,Type.getInternalName(CaptureIrisBufferSource.class),false);writer.visitEnd();
        adapter=loader.define(writer.toByteArray()).getConstructor().newInstance();
        wrap=CaptureIrisBufferSource.class.getDeclaredMethod("piqReplay$buffer",RenderType.class,Operation.class);wrap.setAccessible(true);
        flush=CaptureIrisBufferSource.class.getDeclaredMethod("piqReplay$flush",RenderType.class,Operation.class);flush.setAccessible(true);
        writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"cn/piq/replaycompat/capture/NoGpuIrisBuilder",null,SEGMENT.replace('.','/'),null);
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"sink","L"+VC+";",null,null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"lastType","L"+RT+";",null,null).visitEnd();
        constructor(writer,SEGMENT.replace('.','/'),true);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"getBuffer",IrisBufferContract.GET,null,null);method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD,1);method.visitFieldInsn(Opcodes.PUTSTATIC,"cn/piq/replaycompat/capture/NoGpuIrisBuilder","lastType","L"+RT+";");
        method.visitFieldInsn(Opcodes.GETSTATIC,"cn/piq/replaycompat/capture/NoGpuIrisBuilder","sink","L"+VC+";");
        method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,2);method.visitEnd();writer.visitEnd();
        fixtureBuilder=loader.define(writer.toByteArray());
    }
    private static void constructor(ClassWriter writer,String parent,boolean irisArgument) {
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);
        if(irisArgument)method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,parent,"<init>",irisArgument?"(L"+IRIS.replace('.','/')+";)V":"()V",false);
        method.visitInsn(Opcodes.RETURN);method.visitMaxs(2,1);method.visitEnd();
    }
    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(){super(IrisBufferCaptureTest.class.getClassLoader());}
        Class<?> define(byte[] bytes){return defineClass(null,bytes,0,bytes.length);}
    }
    private static final class HeadlessLoader extends ClassLoader {
        HeadlessLoader(){super(IrisBufferCaptureTest.class.getClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)) {
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null&&(name.equals(RT.replace('/','.'))||name.equals("net.minecraft.client.renderer.MultiBufferSource")
                        ||name.equals("net.minecraft.client.renderer.MultiBufferSource$BufferSource"))) {
                    byte[] bytes=identityMaterialOrBase(name);loaded=defineClass(name,bytes,0,bytes.length);
                }
                if(loaded==null&&(name.startsWith("cn.piq.replaycompat.capture.")||name.equals(CaptureIrisBufferSource.class.getName())
                        ||name.equals(IrisBufferContract.class.getName())||name.startsWith("net.irisshaders.batchedentityrendering.")
                        ||name.equals("net.irisshaders.iris.layer.WrappingMultiBufferSource"))) {
                    try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                        if(in==null)throw new ClassNotFoundException(name);
                        byte[] bytes=in.readAllBytes();loaded=defineClass(name,bytes,0,bytes.length);
                    } catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
                }
                if(loaded==null)loaded=super.loadClass(name,false);
                if(resolve)resolveClass(loaded);return loaded;
            }
        }
    }
    private static byte[] identityMaterialOrBase(String name) {
        String internal=name.replace('.','/');boolean material=internal.equals(RT),inter=internal.equals("net/minecraft/client/renderer/MultiBufferSource");
        var writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC|(inter?Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT:0),internal,null,"java/lang/Object",null);
        if(inter) {
            writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"getBuffer",IrisBufferContract.GET,null,null).visitEnd();
        } else {
            var ctor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(1,1);ctor.visitEnd();
            if(material) for(String methodName:List.of("gui","guiOverlay")) {
                var m=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,methodName,"()L"+RT+";",null,null);m.visitCode();
                m.visitTypeInsn(Opcodes.NEW,RT);m.visitInsn(Opcodes.DUP);m.visitMethodInsn(Opcodes.INVOKESPECIAL,RT,"<init>","()V",false);
                m.visitInsn(Opcodes.ARETURN);m.visitMaxs(2,0);m.visitEnd();
            }
            else {
                var m=writer.visitMethod(Opcodes.ACC_PUBLIC,"getBuffer",IrisBufferContract.GET,null,null);m.visitCode();m.visitInsn(Opcodes.ACONST_NULL);
                m.visitInsn(Opcodes.ARETURN);m.visitMaxs(1,2);m.visitEnd();
            }
        }
        writer.visitEnd();return writer.toByteArray();
    }
}
