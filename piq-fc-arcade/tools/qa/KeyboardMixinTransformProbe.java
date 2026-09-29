import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** Real Sponge transformer, with a read-only classpath host (not fake MC classes).
 * No Minecraft class is defined or initialized; all target checks inspect actual bytecode.
 */
public final class KeyboardMixinTransformProbe {
    private static int checks;
    private static void require(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static String hash(byte[] b)throws Exception{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static ClassNode node(byte[] b){var n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static MethodNode target(ClassNode n){return n.methods.stream().filter(m->m.name.equals("keyPress")&&m.desc.equals("(JIIII)V")).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> ops(MethodNode m){var r=new ArrayList<AbstractInsnNode>();for(var i:m.instructions)if(i.getOpcode()>=0)r.add(i);return r;}
    private static int labelIndex(LabelNode label,List<AbstractInsnNode> code){AbstractInsnNode n=label;while(n!=null&&n.getOpcode()<0)n=n.getNext();return n==null?code.size():code.indexOf(n);}
    private static List<String> instructions(MethodNode m){
        var code=ops(m);var out=new ArrayList<String>();
        for(int at=0;at<code.size();at++){
            var i=code.get(at);String s=""+i.getOpcode();
            if(i instanceof VarInsnNode v)s+=" V"+v.var;
            else if(i instanceof IntInsnNode v)s+=" I"+v.operand;
            else if(i instanceof TypeInsnNode v)s+=" T"+v.desc;
            else if(i instanceof FieldInsnNode v)s+=" F"+v.owner+"."+v.name+v.desc;
            else if(i instanceof MethodInsnNode v)s+=" M"+v.owner+"."+v.name+v.desc+":"+v.itf;
            else if(i instanceof JumpInsnNode v)s+=" J"+(labelIndex(v.label,code)-at);
            else if(i instanceof LdcInsnNode v)s+=" C"+v.cst;
            else if(i instanceof IincInsnNode v)s+=" +"+v.var+":"+v.incr;
            else if(i instanceof InvokeDynamicInsnNode v)s+=" D"+v.name+v.desc+v.bsm+Arrays.deepToString(v.bsmArgs);
            else if(i instanceof TableSwitchInsnNode v){s+=" S"+v.min+":"+v.max+":"+(labelIndex(v.dflt,code)-at);for(var l:v.labels)s+=":"+(labelIndex(l,code)-at);}
            else if(i instanceof LookupSwitchInsnNode v){s+=" L"+v.keys+":"+(labelIndex(v.dflt,code)-at);for(var l:v.labels)s+=":"+(labelIndex(l,code)-at);}
            else if(i instanceof MultiANewArrayInsnNode v)s+=" A"+v.desc+":"+v.dims;
            out.add(s);
        }return out;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("target.class mixin.class Minecraft.class transformed.class");
        byte[] original=Files.readAllBytes(Path.of(args[0])),mixin=Files.readAllBytes(Path.of(args[1]));
        byte[] minecraft=Files.readAllBytes(Path.of(args[2]));
        ClassNode old=node(original),mix=node(mixin);
        require(old.name.equals("net/minecraft/client/KeyboardHandler"),"Actual target name");
        require(mix.name.equals("cn/piq/fcarcade/mixin/KeyboardHandlerMixin"),"Actual compiled mixin name");
        require(old.methods.stream().noneMatch(m->m.name.contains("piq$keyboard")),"Unmodified input has no injection");
        System.setProperty("mixin.bootstrapService",Boot.class.getName());
        System.setProperty("mixin.service",Host.class.getName());
        MixinBootstrap.init();
        var env=MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("piq_fc_keyboard.mixins.json");
        require(MixinService.getService() instanceof Host,"Read-only host selected");
        require(MixinService.getGlobalPropertyService() instanceof Blackboard,"Isolated blackboard selected");
        Host host=(Host)MixinService.getService();
        IMixinTransformer transformer=host.transformer();
        require(transformer.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.MixinTransformer"),"Real Mixin transformer");
        byte[] transformed=transformer.transformClass(env,"net.minecraft.client.KeyboardHandler",original);
        require(transformed!=null&&!Arrays.equals(original,transformed),"Real transform changed target");
        var result=node(transformed);var before=target(old);var after=target(result);
        var handlers=result.methods.stream().filter(m->ops(m).stream().anyMatch(i->i instanceof MethodInsnNode c
                &&c.owner.equals("cn/piq/retro/client/KeyboardInput")&&c.name.equals("intercept")&&c.desc.equals("(JIIII)Z"))).toList();
        require(handlers.size()==1,"Exactly one merged real handler calls our router");
        MethodNode handler=handlers.getFirst();var hc=ops(handler);
        int intercept=-1,cancel=-1;
        for(int i=0;i<hc.size();i++)if(hc.get(i)instanceof MethodInsnNode c){
            if(c.owner.equals("cn/piq/retro/client/KeyboardInput")&&c.name.equals("intercept"))intercept=i;
            if(c.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")&&c.name.equals("cancel"))cancel=i;
        }
        require(intercept>=0&&cancel>intercept,"Handler cancellation follows router");
        require(hc.get(intercept+1)instanceof JumpInsnNode j&&j.getOpcode()==Opcodes.IFEQ,"Router false bypasses cancellation");
        require(labelIndex(((JumpInsnNode)hc.get(intercept+1)).label,hc)>cancel,"Router false cannot reach cancellation");
        var oldInstructions=instructions(before);var newInstructions=instructions(after);
        int prefix=newInstructions.size()-oldInstructions.size();
        require(prefix>0,"Added HEAD prefix");
        require(newInstructions.subList(prefix,newInstructions.size()).equals(oldInstructions),"All original instructions preserved exactly as suffix, including branch targets");
        var ac=ops(after);int invoke=-1,isCancelled=-1,click=-1,screen=-1;
        for(int i=0;i<ac.size();i++)if(ac.get(i)instanceof MethodInsnNode c){
            if(c.owner.equals(result.name)&&c.name.equals(handler.name)&&c.desc.equals(handler.desc))invoke=i;
            if(c.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")&&c.name.equals("isCancelled"))isCancelled=i;
            if(click<0&&c.owner.equals("net/minecraft/client/KeyMapping")&&c.name.equals("click"))click=i;
            if(screen<0&&c.owner.equals("net/minecraft/client/Minecraft")&&c.name.equals("setScreen"))screen=i;
        }
        require(invoke>=0&&invoke<isCancelled&&isCancelled<prefix,"Cancel test resides in actual HEAD prefix");
        require(click>=prefix&&invoke<click,"Injection precedes actual KeyMapping.click");
        require(screen==-1,"Actual keyPress records clicks; it does not directly open vanilla screens");
        ClassNode minecraftNode=node(minecraft);
        require(minecraftNode.name.equals("net/minecraft/client/Minecraft"),"Actual downstream Minecraft class");
        MethodNode keybinds=minecraftNode.methods.stream().filter(m->m.name.equals("handleKeybinds")&&m.desc.equals("()V")).findFirst().orElseThrow();
        var kc=ops(keybinds);int guiGates=0;
        for(String key:List.of("keyInventory","keyChat","keyCommand")){
            int field=-1;
            for(int i=0;i<kc.size();i++)if(kc.get(i)instanceof FieldInsnNode f&&f.owner.equals("net/minecraft/client/Options")&&f.name.equals(key)){require(field<0,"One GUI key read: "+key);field=i;}
            require(field>=0,"Actual GUI key read: "+key);
            require(kc.get(field+1)instanceof MethodInsnNode c&&c.owner.equals("net/minecraft/client/KeyMapping")&&c.name.equals("consumeClick")&&c.desc.equals("()Z"),"GUI requires consumed click: "+key);
            require(kc.get(field+2)instanceof JumpInsnNode j&&j.getOpcode()==Opcodes.IFEQ,"No consumed click bypasses GUI: "+key);
            int end=labelIndex(((JumpInsnNode)kc.get(field+2)).label,kc);boolean opens=false;
            require(end>field+2,"Forward GUI gate: "+key);
            for(int i=field+3;i<end;i++)if(kc.get(i)instanceof MethodInsnNode c&&c.owner.equals(minecraftNode.name)&&c.name.equals(key.equals("keyInventory")?"setScreen":"openChatScreen"))opens=true;
            require(opens,"Actual vanilla opening lies inside click-gated branch: "+key);guiGates++;
        }
        require(ac.get(isCancelled+1)instanceof JumpInsnNode,"Conditional early-exit branch");
        var branch=(JumpInsnNode)ac.get(isCancelled+1);
        require(branch.getOpcode()==Opcodes.IFEQ&&labelIndex(branch.label,ac)==prefix,"False cancellation continues at original first instruction");
        require(ac.get(isCancelled+2).getOpcode()==Opcodes.RETURN,"True cancellation returns before all vanilla instructions");
        require(host.minecraftClassesDefined==0,"No MC target was classloaded");
        require(host.reads.contains("cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class"),"Transformer read actual compiled mixin bytes");
        Files.write(Path.of(args[3]),transformed,StandardOpenOption.CREATE_NEW);
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_mixin_transformer\":true,\"minecraft_started\":false,\"minecraft_classes_defined\":0,\"target_sha256\":\""+hash(original)+"\",\"downstream_minecraft_sha256\":\""+hash(minecraft)+"\",\"mixin_sha256\":\""+hash(mixin)+"\",\"transformed_sha256\":\""+hash(transformed)+"\",\"prefix_instructions\":"+prefix+",\"original_instructions_preserved\":"+oldInstructions.size()+",\"router_before_keymapping_click\":true,\"vanilla_gui_uses_consumed_clicks\":true,\"gui_key_gates\":"+guiGates+",\"cancel_return_before_vanilla\":true,\"host\":\"read-only-classpath-service\",\"transformer_origin\":\""+transformer.getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm()+"\"}");
    }

    public static final class Boot implements IMixinServiceBootstrap {
        public String getName(){return "PIQ offline bytecode audit";}
        public String getServiceClassName(){return Host.class.getName();}
        public void bootstrap(){}
    }
    public static final class Host extends MixinServiceAbstract implements IClassProvider,IClassBytecodeProvider,ITransformerProvider,IClassTracker {
        final Set<String> reads=new HashSet<>();int minecraftClassesDefined;
        public String getName(){return "PIQ read-only bytecode host";}
        public boolean isValid(){return true;}
        public MixinEnvironment.Phase getInitialPhase(){return MixinEnvironment.Phase.DEFAULT;}
        public IClassProvider getClassProvider(){return this;}
        public IClassBytecodeProvider getBytecodeProvider(){return this;}
        public ITransformerProvider getTransformerProvider(){return this;}
        public IClassTracker getClassTracker(){return this;}
        public IMixinAuditTrail getAuditTrail(){return null;}
        public Collection<String> getPlatformAgents(){return List.of();}
        public IContainerHandle getPrimaryContainer(){return new ContainerHandleVirtual("piq-offline-audit");}
        public InputStream getResourceAsStream(String name){return ClassLoader.getSystemResourceAsStream(name);}
        public URL[] getClassPath(){return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(p->{try{return Path.of(p).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);}
        public Class<?> findClass(String name)throws ClassNotFoundException{return findClass(name,false);}
        public Class<?> findClass(String name,boolean initialize)throws ClassNotFoundException{
            if(name.startsWith("net.minecraft.")){minecraftClassesDefined++;throw new ClassNotFoundException("MC definition prohibited: "+name);}
            return Class.forName(name,initialize,ClassLoader.getSystemClassLoader());
        }
        public Class<?> findAgentClass(String n,boolean initialize)throws ClassNotFoundException{return findClass(n,initialize);}
        public ClassNode getClassNode(String name)throws ClassNotFoundException,IOException{return getClassNode(name,false,0);}
        public ClassNode getClassNode(String name,boolean runTransformers)throws ClassNotFoundException,IOException{return getClassNode(name,runTransformers,0);}
        public ClassNode getClassNode(String name,boolean runTransformers,int flags)throws ClassNotFoundException,IOException{
            String resource=name.replace('.','/')+".class";reads.add(resource);
            try(var in=getResourceAsStream(resource)){if(in==null)throw new ClassNotFoundException(name);var node=new ClassNode();new ClassReader(in).accept(node,flags);return node;}
        }
        public Collection<ITransformer> getTransformers(){return List.of();}
        public Collection<ITransformer> getDelegatedTransformers(){return List.of();}
        public void addTransformerExclusion(String name){}
        public void registerInvalidClass(String name){}
        public boolean isClassLoaded(String name){return false;}
        public String getClassRestrictions(String name){return "";}
        IMixinTransformer transformer(){return getInternal(IMixinTransformerFactory.class).createTransformer();}
    }
    public static final class Blackboard implements IGlobalPropertyService {
        private record Key(String name)implements IPropertyKey{}
        private final Map<IPropertyKey,Object> values=new HashMap<>();
        public IPropertyKey resolveKey(String name){return new Key(name);}
        @SuppressWarnings("unchecked") public <T>T getProperty(IPropertyKey key){return (T)values.get(key);}
        public void setProperty(IPropertyKey key,Object value){values.put(key,value);}
        @SuppressWarnings("unchecked") public <T>T getProperty(IPropertyKey key,T fallback){return (T)values.getOrDefault(key,fallback);}
        public String getPropertyString(IPropertyKey key,String fallback){Object value=values.get(key);return value==null?fallback:value.toString();}
    }
}
