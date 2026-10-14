import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.*;

/** Actual production 5-Mixin application against installed NeoForge/ReForgedPlay
 * classes. Does not define MC classes or initialize the game. */
public final class QuickModeProductionTransformProbe {
    static int checks;
    static void require(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static byte[] resource(String name)throws IOException{try(var in=ClassLoader.getSystemResourceAsStream(name)){if(in==null)throw new FileNotFoundException(name);return in.readAllBytes();}}
    static ClassNode node(byte[] bytes){var n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    static List<AbstractInsnNode> ops(MethodNode m){var a=new ArrayList<AbstractInsnNode>();for(var i:m.instructions)if(i.getOpcode()>=0)a.add(i);return a;}
    static int label(LabelNode l,List<AbstractInsnNode> code){AbstractInsnNode n=l;while(n!=null&&n.getOpcode()<0)n=n.getNext();return n==null?code.size():code.indexOf(n);}
    static List<String> signature(MethodNode m){
        var code=ops(m);var result=new ArrayList<String>();
        for(int at=0;at<code.size();at++){
            var i=code.get(at);String s=""+i.getOpcode();
            if(i instanceof VarInsnNode v)s+=" V"+v.var;
            else if(i instanceof IntInsnNode v)s+=" I"+v.operand;
            else if(i instanceof TypeInsnNode v)s+=" T"+v.desc;
            else if(i instanceof FieldInsnNode v)s+=" F"+v.owner+"."+v.name+v.desc;
            else if(i instanceof MethodInsnNode v)s+=" M"+v.owner+"."+v.name+v.desc+":"+v.itf;
            else if(i instanceof JumpInsnNode v)s+=" J"+(label(v.label,code)-at);
            else if(i instanceof LdcInsnNode v)s+=" C"+v.cst;
            else if(i instanceof IincInsnNode v)s+=" +"+v.var+":"+v.incr;
            else if(i instanceof InvokeDynamicInsnNode v)s+=" D"+v.name+v.desc+v.bsm+Arrays.deepToString(v.bsmArgs);
            else if(i instanceof TableSwitchInsnNode v){s+=" S"+v.min+":"+v.max+":"+(label(v.dflt,code)-at);for(var l:v.labels)s+=":"+(label(l,code)-at);}
            else if(i instanceof LookupSwitchInsnNode v){s+=" L"+v.keys+":"+(label(v.dflt,code)-at);for(var l:v.labels)s+=":"+(label(l,code)-at);}
            else if(i instanceof MultiANewArrayInsnNode v)s+=" A"+v.desc+":"+v.dims;
            result.add(s);
        }return result;
    }
    static MethodNode method(ClassNode n,String name){var found=n.methods.stream().filter(m->m.name.equals(name)).toList();require(found.size()==1,"Exactly one target method "+name);return found.getFirst();}
    static MethodNode merged(ClassNode n,String marker){var found=n.methods.stream().filter(m->m.name.contains(marker)&&!m.name.contains("lambda$")).toList();require(found.size()==1,"Exactly one merged handler "+marker);return found.getFirst();}
    static long calls(MethodNode m,String owner,String name){return ops(m).stream().filter(i->i instanceof MethodInsnNode c&&c.owner.equals(owner)&&c.name.equals(name)).count();}
    static void redirect(ClassNode before,ClassNode after,String methodName,String oldOwner,String oldName,String marker){
        MethodNode b=method(before,methodName),a=method(after,methodName),handler=merged(after,marker);
        require(calls(b,oldOwner,oldName)>=1,"Original callsite exists: "+marker);
        require(calls(a,after.name,handler.name)==1,"Exactly one production redirect: "+marker);
        long expected=calls(b,oldOwner,oldName)-1;
        require(calls(a,oldOwner,oldName)==expected,"Only reviewed invocation replaced: "+marker);
        // Original non-stack instructions stay in order, except the redirected call.
        var wanted=new ArrayList<String>();var found=new ArrayList<String>();
        for(var i:ops(b))if(!(i instanceof VarInsnNode)&&!(i instanceof JumpInsnNode)&&!(i instanceof MethodInsnNode c&&c.owner.equals(oldOwner)&&c.name.equals(oldName)))wanted.add(stable(i));
        for(var i:ops(a))if(!(i instanceof VarInsnNode)&&!(i instanceof JumpInsnNode))found.add(stable(i));
        int index=0;for(String value:found)if(index<wanted.size()&&wanted.get(index).equals(value))index++;
        require(index==wanted.size(),"Original operations retained in order: "+marker);
        require(b.tryCatchBlocks.size()==a.tryCatchBlocks.size(),"Original exception handlers retained: "+marker);
        // Undo exactly Sponge's reviewed local-spill/receiver-load redirect prefix.
        // With that injected prefix and call removed, every original instruction and
        // relative jump target must match; no looser subsequence claim is sufficient.
        var restored=new MethodNode();a.accept(restored);
        MethodInsnNode inserted=(MethodInsnNode)ops(restored).stream().filter(i->i instanceof MethodInsnNode c&&c.owner.equals(after.name)&&c.name.equals(handler.name)).findFirst().orElseThrow();
        AbstractInsnNode block=inserted.getPrevious();while(block.getPrevious() instanceof VarInsnNode)block=block.getPrevious();
        while(block!=inserted && !(block instanceof VarInsnNode v&&v.var>=b.maxLocals&&v.getOpcode()>=Opcodes.ISTORE&&v.getOpcode()<=Opcodes.ASTORE))block=block.getNext();
        require(block!=inserted,"Recognized Sponge temporary spill prefix: "+marker);
        while(block!=inserted){AbstractInsnNode next=block.getNext();require(block instanceof VarInsnNode,"Redirect prefix consists only of local stack plumbing: "+marker);restored.instructions.remove(block);block=next;}
        MethodInsnNode oldCall=(MethodInsnNode)ops(b).stream().filter(i->i instanceof MethodInsnNode c&&c.owner.equals(oldOwner)&&c.name.equals(oldName)).findFirst().orElseThrow();
        restored.instructions.set(inserted,new MethodInsnNode(oldCall.getOpcode(),oldCall.owner,oldCall.name,oldCall.desc,oldCall.itf));
        require(signature(restored).equals(signature(b)),"Undo reviewed redirect restores ALL original instructions/jump targets exactly: "+marker);
        var oldOps=ops(b);var restoredOps=ops(restored);
        for(int i=0;i<b.tryCatchBlocks.size();i++){var x=b.tryCatchBlocks.get(i);var y=restored.tryCatchBlocks.get(i);require(Objects.equals(x.type,y.type)&&label(x.start,oldOps)==label(y.start,restoredOps)&&label(x.end,oldOps)==label(y.end,restoredOps)&&label(x.handler,oldOps)==label(y.handler,restoredOps),"Original exception region exact after redirect undo: "+marker);}
    }
    static String stable(AbstractInsnNode i){
        if(i instanceof MethodInsnNode c)return i.getOpcode()+" M"+c.owner+"."+c.name+c.desc;
        if(i instanceof FieldInsnNode c)return i.getOpcode()+" F"+c.owner+"."+c.name+c.desc;
        if(i instanceof TypeInsnNode c)return i.getOpcode()+" T"+c.desc;
        if(i instanceof LdcInsnNode c)return i.getOpcode()+" C"+c.cst;
        if(i instanceof InvokeDynamicInsnNode c)return i.getOpcode()+" D"+c.name+c.desc+c.bsm+Arrays.deepToString(c.bsmArgs);
        if(i instanceof IntInsnNode c)return i.getOpcode()+" I"+c.operand;
        if(i instanceof IincInsnNode c)return i.getOpcode()+" +"+c.var+":"+c.incr;
        return ""+i.getOpcode();
    }
    static void constructorTail(ClassNode before,ClassNode after,String marker){
        MethodNode handler=merged(after,marker);int sites=0;
        for(var b:before.methods)if(b.name.equals("<init>")){
            var a=after.methods.stream().filter(m->m.name.equals(b.name)&&m.desc.equals(b.desc)).findFirst().orElseThrow();
            var old=signature(b);var now=signature(a);require(old.getLast().equals("177"),"Constructor ends RETURN");
            require(now.getLast().equals("177"),"Injected constructor ends RETURN");
            require(now.subList(0,old.size()-1).equals(old.subList(0,old.size()-1)),"Original constructor body exact prefix: "+marker);
            require(calls(a,after.name,handler.name)==1,"One tail constructor hook: "+marker);sites++;
        }require(sites==1,"One actual target constructor: "+marker);
    }
    static Map<String,Object> map(Object... a){var result=new LinkedHashMap<String,Object>();for(int i=0;i<a.length;i+=2)result.put(a[i].toString(),a[i+1]);return result;}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("output-directory build-result.json");
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var build=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        Path artifact=Path.of(build.get("artifact").getAsString());
        require(hash(Files.readAllBytes(artifact)).equals(build.get("sha256").getAsString()),"Production artifact matches build receipt");
        System.setProperty("mixin.bootstrapService",KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service",KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();var environment=MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var host=(KeyboardMixinTransformProbe.Host)MixinService.getService();var transformer=host.transformer();
        Mixins.addConfiguration("piq_replay_quickmode_compat.mixins.json");
        String configuration="net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
        String sender="com.replaymod.replay.QuickReplaySender",entity="com.replaymod.replaystudio.rar.state.Entity",dispatch=sender+"$1";
        String indicator="com.replaymod.extras.QuickMode";
        Map<String,ClassNode> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();List<Object> results=new ArrayList<>();int exactUnchanged=0;
        for(String target:List.of(configuration,sender,entity,dispatch,indicator)){
            byte[] original=resource(target.replace('.','/')+".class");byte[] transformed=transformer.transformClass(environment,target,original);
            if(target.equals(configuration)){
                require(hash(original).equals("5ae633fab8c0e44ad48031f73a226538a997bd1e76e3657028323e17408195b7"),"Exact reviewed NeoForge 21.1.250 target bytes");
            }else{
                var connection=(java.net.JarURLConnection)ClassLoader.getSystemResource(target.replace('.','/')+".class").openConnection();
                Path source=Path.of(connection.getJarFileURL().toURI());
                require(hash(Files.readAllBytes(source)).equals(build.get("target_replay_sha256").getAsString()),"Replay target belongs to build-receipt JAR: "+target);
            }
            require(transformed!=null&&!Arrays.equals(original,transformed),"Applied production mixin: "+target);
            ClassNode b=node(original),a=node(transformed);before.put(target,b);after.put(target,a);
            var fields=new HashSet<String>();for(var f:a.fields)require(fields.add(f.name+":"+f.desc),"No duplicate fields "+target);
            var methods=new HashSet<String>();for(var m:a.methods){require(methods.add(m.name+m.desc),"No duplicate methods "+target);if((m.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0)new Analyzer<>(new BasicVerifier()).analyze(a.name,m);}
            for(var m:b.methods){var changed=a.methods.stream().filter(v->v.name.equals(m.name)&&v.desc.equals(m.desc)).findFirst().orElseThrow();if(signature(m).equals(signature(changed)))exactUnchanged++;}
            Files.write(out.resolve(target.substring(target.lastIndexOf('.')+1)+".class"),transformed);
            var dump=new StringWriter();new ClassReader(transformed).accept(new TraceClassVisitor(new PrintWriter(dump)),0);Files.writeString(out.resolve(target.substring(target.lastIndexOf('.')+1)+".asm.txt"),dump.toString());
            results.add(map("target",target,"originalSha256",hash(original),"transformedSha256",hash(transformed),"originalMethods",b.methods.size(),"transformedMethods",a.methods.size()));
        }
        var bc=before.get(configuration);var ac=after.get(configuration);
        for(String field:List.of("connectionType","initializedConnection"))require(ac.fields.stream().filter(f->f.name.equals(field)).count()==1,"Shadow uses original single field "+field);
        constructorTail(bc,ac,"piq$preserveQuickReplayHandshake");
        var bs=before.get(sender);var as=after.get(sender);
        require(!bs.interfaces.contains("cn/piq/replayquick/QuickSableAccess")&&as.interfaces.contains("cn/piq/replayquick/QuickSableAccess"),"QuickSableAccess interface merged");
        require(as.fields.stream().filter(f->f.name.contains("piq$sable")&&f.desc.equals("Lcn/piq/replayquick/QuickSableState;")).count()==1,"Unique per-sender state field");
        require(calls(merged(as,"piq$sableState"),as.name,"piq$sableState")==0,"Accessor does not recurse");
        constructorTail(bs,as,"piq$ownIndex");
        redirect(bs,as,"lambda$initialize$2","com/replaymod/replaystudio/rar/RandomAccessReplay","load","piq$loadFlags");
        redirect(bs,as,"lambda$sendPacketsTill$5","com/replaymod/replaystudio/rar/RandomAccessReplay","seek","piq$seekWithTarget");
        var load=merged(as,"piq$loadFlags");require(calls(load,"cn/piq/replayquick/QuickSableState","load")==1&&calls(load,"com/replaymod/replaystudio/rar/RandomAccessReplay","load")==1,"Index load then real RAR load retained");
        require(calls(merged(as,"piq$seekWithTarget"),"cn/piq/replayquick/QuickSableState","seek")==1,"Seek delegates once to target-aware state");
        var be=before.get(entity);var ae=after.get(entity);
        redirect(be,ae,"lambda$new$0","com/replaymod/replaystudio/rar/PacketSink","accept","piq$markGeneratedTeleport");
        require(calls(merged(ae,"piq$markGeneratedTeleport"),"cn/piq/replayquick/QuickSableScope","generated")==1,"Generated marker applied once");
        require(calls(method(ae,"lambda$new$0"),"com/replaymod/replaystudio/rar/PacketSink","accept")==1,"Head-look sink remains unwrapped");
        var bd=before.get(dispatch);var ad=after.get(dispatch);
        redirect(bd,ad,"dispatch","io/netty/channel/ChannelPipeline","fireChannelRead","piq$completeGeneratedPlayerPosition");
        require(calls(merged(ad,"piq$completeGeneratedPlayerPosition"),"cn/piq/replayquick/QuickSableState","dispatch")==1,"Dispatch hook delegates exactly once");
        var bi=before.get(indicator);var ai=after.get(indicator);
        require(!bi.interfaces.contains("cn/piq/replayquick/mixin/QuickModeIndicatorAccessor")&&ai.interfaces.contains("cn/piq/replayquick/mixin/QuickModeIndicatorAccessor"),"Indicator invoker interface merged");
        for(var original:bi.methods){var retained=ai.methods.stream().filter(m->m.name.equals(original.name)&&m.desc.equals(original.desc)).findFirst().orElseThrow();require(signature(original).equals(signature(retained)),"Every original QuickMode method preserved exactly: "+original.name);}
        MethodNode invoker=method(ai,"piq$updateIndicator");
        require((invoker.access&Opcodes.ACC_PUBLIC)!=0,"Indicator invoker public");
        require(invoker.desc.equals("(Lcom/replaymod/replay/gui/overlay/GuiReplayOverlay;Z)V"),"Indicator invoker exact argument descriptor");
        var invokeCode=ops(invoker);require(invokeCode.size()==5,"Invoker is only argument loads, original call, return");
        require(invokeCode.get(0) instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==0,"Invoker loads original receiver");
        require(invokeCode.get(1) instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==1,"Invoker forwards overlay");
        require(invokeCode.get(2) instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ILOAD&&v.var==2,"Invoker forwards enabled flag");
        require(invokeCode.get(3) instanceof MethodInsnNode c&&c.getOpcode()==Opcodes.INVOKESPECIAL&&c.owner.equals(ai.name)&&c.name.equals("updateIndicator")&&c.desc.equals(invoker.desc),"Invoker calls exact original private method once");
        require(invokeCode.get(4).getOpcode()==Opcodes.RETURN,"Invoker returns without extra behavior");
        require(host.minecraftClassesDefined==0,"No game class initialized through transform host");
        Map<String,String> mixinHashes=new LinkedHashMap<>();try(var jar=new java.util.jar.JarFile(artifact.toFile())){for(String n:List.of("QuickConfigurationMixin","QuickSableSenderMixin","QuickSableEntityMixin","QuickSableDispatchMixin","QuickModeIndicatorAccessor")){String path="cn/piq/replayquick/mixin/"+n+".class";require(host.reads.contains(path),"Actual production mixin read: "+n);byte[] compiled=resource(path);try(var stream=jar.getInputStream(jar.getJarEntry(path))){require(Arrays.equals(compiled,stream.readAllBytes()),"Transformed mixin is byte-identical to production JAR: "+n);}mixinHashes.put(n,hash(compiled));}}
        var result=map("ok",true,"assertions",checks,"productionVersion",build.get("version").getAsString(),"productionArtifactSha256",build.get("sha256").getAsString(),"realSpongeTransformer",true,"allFiveProductionMixinsApplied",true,"indicatorInvokerCallsExactOriginalMethod",true,"allOriginalQuickModeMethodsPreserved",true,"mixinClassSha256",mixinHashes,"targets",results,"exactlyUnchangedOriginalMethods",exactUnchanged,"originalConstructorBodiesPreservedAsExactPrefix",true,"reviewedRedirectSitesAndOriginalOperationsPreserved",true,"allFourRedirectsUndoToExactOriginalInstructionsAndExceptionRegions",true,"allTransformedMethodsPassAsmBasicVerifier",true,"duplicateFieldsOrMethods",false,"minecraftClassesDefinedByTransformHost",host.minecraftClassesDefined,"minecraftStarted",false,"liveClientValidation",false);
        String json=new GsonBuilder().setPrettyPrinting().create().toJson(result);Files.writeString(out.resolve("verification.json"),json);System.out.println(json);
    }
}
