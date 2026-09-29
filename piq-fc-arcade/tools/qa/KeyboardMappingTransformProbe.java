import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.*;

/** Real Sponge accessor transform + real KeyMapping/ToggleKeyMapping instances.
 * No Minecraft singleton, window, player, input device, or game is started.
 */
public final class KeyboardMappingTransformProbe {
    private static int checks;
    private static void require(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static String hash(byte[] b)throws Exception{return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static byte[] resource(String name)throws Exception{try(var in=ClassLoader.getSystemResourceAsStream(name)){if(in==null)throw new FileNotFoundException(name);return in.readAllBytes();}}
    private static ClassNode node(byte[] bytes){var n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    private static List<AbstractInsnNode> ops(MethodNode method){var r=new ArrayList<AbstractInsnNode>();for(var i:method.instructions)if(i.getOpcode()>=0)r.add(i);return r;}
    private static Object normalized(MethodNode method)throws Exception{var m=KeyboardMixinTransformProbe.class.getDeclaredMethod("instructions",MethodNode.class);m.setAccessible(true);return m.invoke(null,method);}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("new transformed-KeyMapping.class output");
        byte[] original=resource("net/minecraft/client/KeyMapping.class"),accessor=resource("cn/piq/fcarcade/mixin/KeyMappingStateAccess.class");
        System.setProperty("mixin.bootstrapService",KeyboardMixinTransformProbe.Boot.class.getName());
        System.setProperty("mixin.service",KeyboardMixinTransformProbe.Host.class.getName());
        MixinBootstrap.init();var env=MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("piq_fc_keyboard.mixins.json");
        var host=(KeyboardMixinTransformProbe.Host)MixinService.getService();var transformer=host.transformer();
        byte[] transformed=transformer.transformClass(env,"net.minecraft.client.KeyMapping",original);
        require(transformed!=null&&!Arrays.equals(original,transformed),"Actual accessor transform changes KeyMapping");
        ClassNode old=node(original),next=node(transformed);
        require(next.interfaces.contains("cn/piq/fcarcade/mixin/KeyMappingStateAccess"),"Accessor interface merged into actual target");
        for(var method:old.methods){var found=next.methods.stream().filter(m->m.name.equals(method.name)&&m.desc.equals(method.desc)).findFirst().orElseThrow();require(normalized(method).equals(normalized(found)),"Original method preserved: "+method.name+method.desc);}
        require(next.methods.size()==old.methods.size()+2,"Only two accessor methods added");
        require(next.fields.size()==old.fields.size(),"No state fields or bindings added");
        for(String name:List.of("piq$setDown","piq$setClicks")){
            var method=next.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();var instructions=ops(method);
            require(instructions.size()==4,"Accessor is only receiver, argument, field write, return");
            require(instructions.get(2)instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(old.name)&&f.name.equals(name.endsWith("Down")?"isDown":"clickCount"),"Exact transient field only: "+name);
        }
        byte[] minecraft=resource("net/minecraft/client/Minecraft.class");
        var keybinds=node(minecraft).methods.stream().filter(m->m.name.equals("handleKeybinds")&&m.desc.equals("()V")).findFirst().orElseThrow();
        var code=ops(keybinds);int l=-1;
        for(int i=0;i<code.size();i++)if(code.get(i)instanceof FieldInsnNode f&&f.owner.equals("net/minecraft/client/Options")&&f.name.equals("keyAdvancements")){require(l==-1,"Single advancements key read");l=i;}
        require(l>=0,"Actual Minecraft advancements read exists");
        require(code.get(l+1)instanceof MethodInsnNode m&&m.name.equals("consumeClick")&&m.desc.equals("()Z"),"L advancements depends on actual click queue");
        require(code.get(l+2)instanceof JumpInsnNode j&&j.getOpcode()==Opcodes.IFEQ,"No click bypasses advancements GUI");
        var branch=(JumpInsnNode)code.get(l+2);AbstractInsnNode end=branch.label;while(end!=null&&end.getOpcode()<0)end=end.getNext();int limit=code.indexOf(end);boolean opens=false;
        for(int i=l+3;i<limit;i++)if(code.get(i)instanceof MethodInsnNode m&&m.owner.equals("net/minecraft/client/Minecraft")&&m.name.equals("setScreen"))opens=true;
        require(opens,"Advancements screen is inside click-gated branch");
        URL[] urls=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(p->{try{return Path.of(p).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
        // Actual dependencies are loaded normally. Only the one production target
        // is substituted with the real transform result, not a mock MC class.
        try(var loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader()){
            @Override protected Class<?> findClass(String name)throws ClassNotFoundException{
                if(name.equals("net.minecraft.client.Minecraft"))throw new ClassNotFoundException("Minecraft singleton prohibited");
                if(name.equals("net.minecraft.client.KeyMapping"))return defineClass(name,transformed,0,transformed.length);
                return super.findClass(name);
            }
        }){
            Class<?> mapping=loader.loadClass("net.minecraft.client.KeyMapping"),toggle=loader.loadClass("net.minecraft.client.ToggleKeyMapping"),type=loader.loadClass("com.mojang.blaze3d.platform.InputConstants$Type");
            Class<?> helper=loader.loadClass("cn.piq.retro.client.KeyboardMappingState");
            Object keysym=type.getField("KEYSYM").get(null),mouse=type.getField("MOUSE").get(null),scan=type.getField("SCANCODE").get(null);
            var constructor=mapping.getConstructor(String.class,type,int.class,String.class);
            Object advancement=constructor.newInstance("piq.audit.advancements",keysym,76,"key.categories.misc");
            Object inventory=constructor.newInstance("piq.audit.inventory",keysym,69,"key.categories.misc");
            Object use=constructor.newInstance("piq.audit.use",mouse,1,"key.categories.misc");
            Object scanKey=constructor.newInstance("piq.audit.scan",scan,17,"key.categories.misc");
            Object crouch=toggle.getConstructor(String.class,int.class,String.class,BooleanSupplier.class).newInstance("piq.audit.toggle",340,"key.categories.misc",(BooleanSupplier)()->true);
            Field down=mapping.getDeclaredField("isDown"),clicks=mapping.getDeclaredField("clickCount");down.setAccessible(true);clicks.setAccessible(true);
            Method consume=mapping.getMethod("consumeClick"),getKey=mapping.getMethod("getKey"),clear=helper.getMethod("clear",mapping);
            Object[] values={advancement,inventory,use,scanKey,crouch};Object[] identities=new Object[values.length];
            for(int i=0;i<values.length;i++){identities[i]=getKey.invoke(values[i]);down.setBoolean(values[i],true);clicks.setInt(values[i],3);}
            // Seed only transient fields to avoid a native window in KeyMappingLookup.
            // The production ToggleKeyMapping setter itself proves the old failure.
            toggle.getMethod("setDown",boolean.class).invoke(crouch,false);
            require(down.getBoolean(crouch),"Actual toggle setter false leaves sticky down state (pre-fix reproduction)");
            clear.invoke(null,crouch);
            require(!down.getBoolean(crouch),"Production clear resets actual toggle state");
            require(!(boolean)consume.invoke(crouch),"Production clear drains actual toggle click queue");
            Object array=Array.newInstance(mapping,values.length);for(int i=0;i<values.length;i++)Array.set(array,i,values[i]);
            helper.getMethod("clearKeyboard",array.getClass()).invoke(null,array);
            for(int i:new int[]{0,1,3,4}){require(!down.getBoolean(values[i]),"Keyboard held state cleared "+i);require(!(boolean)consume.invoke(values[i]),"No queued MC action after transition "+i);require(getKey.invoke(values[i])==identities[i],"Binding object unchanged "+i);}
            require(down.getBoolean(use),"Mouse right-use down state preserved");
            for(int i=0;i<3;i++)require((boolean)consume.invoke(use),"Mouse use queued clicks preserved "+i);
            require(!(boolean)consume.invoke(use),"Mouse click count unchanged exactly");
            require(getKey.invoke(use)==identities[2],"Mouse binding unchanged");
            // Genuine click consumption works again after clearing; no persistent unbind.
            clicks.setInt(advancement,1);require((boolean)consume.invoke(advancement),"New free-mode MC click works after a clear");require(!(boolean)consume.invoke(advancement),"New click consumed once only");
            clear.invoke(null,advancement);clear.invoke(null,advancement);require(!(boolean)consume.invoke(advancement),"Clear remains idempotent");
        }
        Files.write(Path.of(args[0]),transformed,StandardOpenOption.CREATE_NEW);
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_mixin_transformer\":true,\"actual_keymapping_instances\":true,\"actual_toggle_failure_reproduced\":true,\"advancements_click_gate_verified\":true,\"keyboard_queue_clear_and_mouse_preservation\":true,\"minecraft_started\":false,\"target_sha256\":\""+hash(original)+"\",\"accessor_sha256\":\""+hash(accessor)+"\",\"transformed_sha256\":\""+hash(transformed)+"\",\"helper_sha256\":\""+hash(resource("cn/piq/retro/client/KeyboardMappingState.class"))+"\"}");
    }
}
