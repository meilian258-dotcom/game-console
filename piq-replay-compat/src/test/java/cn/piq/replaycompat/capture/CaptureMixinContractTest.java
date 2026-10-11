package cn.piq.replaycompat.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static cn.piq.replaycompat.capture.CaptureAssertions.*;

/** Reads compiled adapter and real target bytecode; never initializes Minecraft or emulator classes. */
public final class CaptureMixinContractTest {
    private static int hooks,targets,optionalMissing,shadows,shadowFields,staticRedirects,instanceRedirects,interfaceRedirects;
    public static void main(String[] args) throws Exception {
        var config=Path.of("piq-replay-compat/src/main/resources/piq_replay_compat.mixins.json");
        if(!Files.isRegularFile(config))config=Path.of("src/main/resources/piq_replay_compat.mixins.json");
        String manifest=Files.readString(config);
        ReplayApiContract.verify(name -> CaptureMixinContractTest.class.getClassLoader().getResourceAsStream(name));
        for(String required:List.of("GuardCustomPayload","ReplayOpenGuardMixin","ReplayForceStopMixin"))
            assertTrue(manifest.contains("\""+required+"\""),"required safety adapter omitted: "+required);
        var matcher=Pattern.compile("\"((?:Capture|Guard|Replay)[A-Za-z0-9]+)\"").matcher(manifest);
        int mixins=0;
        while(matcher.find()) { verify(matcher.group(1)); mixins++; }
        assertTrue(mixins>=25,"capture/guard/replay manifest unexpectedly incomplete");
        assertTrue(hooks>=10,"required hooks were not checked");
        assertTrue(staticRedirects>0&&instanceRedirects>0&&interfaceRedirects>0,"all redirect invocation forms must be verified");
        System.out.println("CaptureMixinContractTest: "+mixins+" configured adapters, "+targets+" actual targets, "+hooks
                +" exact bytecode hooks, "+shadows+" shadow methods and "+shadowFields+" shadow fields passed; "
                +staticRedirects+" static/"+instanceRedirects+" virtual/"+interfaceRedirects+" interface redirects; "
                +optionalMissing+" optional absent targets not validated");
    }

    private static void verify(String simpleName) throws Exception {
        ClassNode adapter=read("cn/piq/replaycompat/mixin/"+simpleName);
        assertTrue(adapter!=null,"missing compiled adapter "+simpleName);
        AnnotationNode mixin=annotation(adapter.invisibleAnnotations,"/Mixin;");
        if(mixin==null)mixin=annotation(adapter.visibleAnnotations,"/Mixin;");
        assertTrue(mixin!=null,"missing @Mixin on "+simpleName);
        List<String> targetNames=new ArrayList<>();
        for(Object type:list(value(mixin,"value")))targetNames.add(((Type)type).getInternalName());
        for(Object name:list(value(mixin,"targets")))targetNames.add(name.toString().replace('.','/'));
        assertFalse(targetNames.isEmpty());
        for(String targetName:targetNames) {
            assertFalse(targetName.startsWith("cn/piq/fcarcade/"),"public route must not hook FC internals: "+targetName);
            ClassNode target=read(targetName);
            if(target==null) {
                assertTrue((targetName.startsWith("cn/piq/")&&!targetName.startsWith("cn/piq/fcarcade/"))
                        ||targetName.equals(IrisBufferContract.TARGET.replace('.','/')),"missing required target "+targetName);
                optionalMissing++;continue;
            }
            targets++;
            for(FieldNode field:adapter.fields) {
                if(annotation(field.visibleAnnotations,"/Shadow;")==null&&annotation(field.invisibleAnnotations,"/Shadow;")==null)continue;
                var matches=target.fields.stream().filter(value->value.name.equals(field.name)&&value.desc.equals(field.desc)).toList();
                assertEquals(1,matches.size());
                assertEquals((field.access&Opcodes.ACC_STATIC)!=0,(matches.getFirst().access&Opcodes.ACC_STATIC)!=0);
                if(annotation(field.visibleAnnotations,"/Final;")!=null||annotation(field.invisibleAnnotations,"/Final;")!=null)
                    assertTrue((matches.getFirst().access&Opcodes.ACC_FINAL)!=0,simpleName+" shadow final field changed: "+field.name);
                shadowFields++;
            }
            for(MethodNode hook:adapter.methods) {
                List<AnnotationNode> annotations=new ArrayList<>();
                if(hook.visibleAnnotations!=null)annotations.addAll(hook.visibleAnnotations);
                if(hook.invisibleAnnotations!=null)annotations.addAll(hook.invisibleAnnotations);
                for(AnnotationNode annotation:annotations) {
                    if(annotation.desc.endsWith("/Shadow;")) {
                        var matches=target.methods.stream().filter(method->method.name.equals(hook.name)&&method.desc.equals(hook.desc)).toList();
                        assertEquals(1,matches.size());
                        assertEquals((hook.access&Opcodes.ACC_STATIC)!=0,(matches.getFirst().access&Opcodes.ACC_STATIC)!=0);
                        shadows++;
                        continue;
                    }
                    boolean inject=annotation.desc.endsWith("/Inject;"),redirect=annotation.desc.endsWith("/Redirect;"),wrap=annotation.desc.endsWith("/WrapMethod;");
                    if(wrap) {
                        for(Object selector:list(value(annotation,"method"))) {
                            var matches=target.methods.stream().filter(method->matches(method,selector.toString())).toList();
                            assertEquals(1,matches.size());MethodNode actual=matches.getFirst();
                            assertEquals((actual.access&Opcodes.ACC_STATIC)!=0,(hook.access&Opcodes.ACC_STATIC)!=0);
                            Type[] supplied=Type.getArgumentTypes(hook.desc),args=Type.getArgumentTypes(actual.desc);
                            assertEquals(args.length+1,supplied.length);
                            for(int i=0;i<args.length;i++)assertEquals(args[i],supplied[i]);
                            assertEquals("com/llamalad7/mixinextras/injector/wrapoperation/Operation",supplied[args.length].getInternalName());
                            assertEquals(Type.getReturnType(actual.desc),Type.getReturnType(hook.desc));hooks++;
                        }
                        continue;
                    }
                    if(!inject&&!redirect)continue;
                    assertEquals(1,((Number)value(annotation,"require")).intValue());
                    for(Object selector:list(value(annotation,"method"))) {
                        var matches=target.methods.stream().filter(method->matches(method,selector.toString())).toList();
                        assertFalse(matches.isEmpty(),simpleName+" missing "+targetName+"."+selector);
                        for(MethodNode targetMethod:matches) {
                            assertEquals((targetMethod.access&Opcodes.ACC_STATIC)!=0,(hook.access&Opcodes.ACC_STATIC)!=0);
                            if(inject)verifyCallback(hook,targetMethod);
                            else verifyRedirect(annotation,hook,targetMethod,simpleName);
                            hooks++;
                        }
                    }
                }
            }
        }
    }

    private static void verifyCallback(MethodNode hook,MethodNode target) {
        Type[] supplied=Type.getArgumentTypes(hook.desc),actual=Type.getArgumentTypes(target.desc);
        assertTrue(supplied.length>=1);
        String callback=supplied[supplied.length-1].getInternalName();
        boolean returnsVoid=Type.getReturnType(target.desc).equals(Type.VOID_TYPE);
        assertEquals("org/spongepowered/asm/mixin/injection/callback/"+(returnsVoid?"CallbackInfo":"CallbackInfoReturnable"),callback);
        assertEquals(Type.VOID_TYPE,Type.getReturnType(hook.desc));
        // Callback-only is legal; these adapters otherwise capture the complete target arguments.
        assertTrue(supplied.length==1||supplied.length-1==actual.length);
        for(int i=0;i<supplied.length-1;i++)assertEquals(actual[i],supplied[i]);
    }

    private static void verifyRedirect(AnnotationNode annotation,MethodNode hook,MethodNode target,String adapter)throws IOException {
        AnnotationNode at=(AnnotationNode)value(annotation,"at");
        assertEquals("INVOKE",value(at,"value"));
        String selector=(String)value(at,"target");
        int separator=selector.indexOf(';'),parameters=selector.indexOf('(',separator);
        String owner=selector.substring(1,separator),name=selector.substring(separator+1,parameters),descriptor=selector.substring(parameters);
        int found=0;
        for(var instruction:target.instructions)if(instruction instanceof MethodInsnNode invoke
                &&invoke.owner.equals(owner)&&invoke.name.equals(name)&&invoke.desc.equals(descriptor)) {
            found++;
            Type[] arguments=Type.getArgumentTypes(descriptor);
            if(invoke.getOpcode()==Opcodes.INVOKESTATIC) {
                staticRedirects++;
                assertEquals(descriptor,hook.desc);
            } else {
                assertTrue(invoke.getOpcode()==Opcodes.INVOKEVIRTUAL||invoke.getOpcode()==Opcodes.INVOKEINTERFACE,
                        adapter+" unexpected redirect opcode: "+invoke.getOpcode());
                Type[] receiverAndArguments=new Type[arguments.length+1];
                receiverAndArguments[0]=Type.getObjectType(owner);
                System.arraycopy(arguments,0,receiverAndArguments,1,arguments.length);
                assertEquals(Type.getMethodDescriptor(Type.getReturnType(descriptor),receiverAndArguments),hook.desc);
                if(invoke.getOpcode()==Opcodes.INVOKEINTERFACE) interfaceRedirects++; else instanceRedirects++;
            }
            ClassNode invokedOwner=read(owner);
            assertTrue(invokedOwner!=null,"missing redirect owner "+owner);
            assertEquals((invokedOwner.access&Opcodes.ACC_INTERFACE)!=0,invoke.itf);
            MethodNode declaration=findMethod(invokedOwner,name,descriptor,new HashSet<>());
            assertTrue(declaration!=null,"missing redirect declaration "+owner+"."+name+descriptor);
            assertEquals(invoke.getOpcode()==Opcodes.INVOKESTATIC,(declaration.access&Opcodes.ACC_STATIC)!=0);
        }
        assertTrue(found>=1,adapter+" redirect instruction missing in "+target.name+target.desc+": "+selector);
    }

    private static boolean matches(MethodNode method,String selector) {
        return selector.indexOf('(')<0?method.name.equals(selector):(method.name+method.desc).equals(selector);
    }
    private static MethodNode findMethod(ClassNode owner,String name,String descriptor,Set<String> visited)throws IOException {
        if(owner==null||!visited.add(owner.name))return null;
        for(var method:owner.methods)if(method.name.equals(name)&&method.desc.equals(descriptor))return method;
        for(String parent:owner.interfaces) {
            var method=findMethod(read(parent),name,descriptor,visited);
            if(method!=null)return method;
        }
        return owner.superName==null?null:findMethod(read(owner.superName),name,descriptor,visited);
    }
    private static ClassNode read(String name)throws IOException {
        try(var bytes=CaptureMixinContractTest.class.getClassLoader().getResourceAsStream(name+".class")) {
            if(bytes==null)return null;
            var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
        }
    }
    private static AnnotationNode annotation(List<AnnotationNode> annotations,String suffix) {
        if(annotations!=null)for(var value:annotations)if(value.desc.endsWith(suffix))return value;
        return null;
    }
    private static Object value(AnnotationNode annotation,String key) {
        if(annotation.values!=null)for(int i=0;i<annotation.values.size();i+=2)
            if(key.equals(annotation.values.get(i)))return annotation.values.get(i+1);
        return null;
    }
    private static List<?> list(Object value) { return value instanceof List<?> list?list:List.of(); }
}
