import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import cn.piq.retro.client.*;
import net.neoforged.neoforge.client.event.InputEvent;

/** Real canceled NeoForge event + production state, with bytecode-only GUI verification. */
public final class ControlSettings34Probe implements Opcodes {
    private static int checks;
    private static final String K="cn/piq/retro/client/";
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static ClassNode node(String name)throws Exception{
        try(var in=ControlSettings34Probe.class.getClassLoader().getResourceAsStream(name+".class")){
            check(in!=null,"missing "+name);ClassNode n=new ClassNode();new ClassReader(in).accept(n,0);return n;
        }
    }
    private static MethodNode method(ClassNode n,String name,String descriptor){return n.methods.stream().filter(m->m.name.equals(name)&&(descriptor==null||descriptor.equals(m.desc))).findFirst().orElseThrow();}
    private static int call(MethodNode m,String owner,String name){int i=0;for(var n:m.instructions){if(n instanceof MethodInsnNode c&&(owner==null||c.owner.equals(owner))&&c.name.equals(name))return i;i++;}return -1;}
    private static boolean field(MethodNode m,String owner,String name,int opcode){for(var n:m.instructions)if(n instanceof FieldInsnNode f&&f.owner.equals(owner)&&f.name.equals(name)&&f.getOpcode()==opcode)return true;return false;}
    private static boolean constant(MethodNode m,String text){for(var n:m.instructions)if(n instanceof LdcInsnNode c&&text.equals(c.cst))return true;return false;}
    private static AbstractInsnNode next(AbstractInsnNode n){do{n=n.getNext();}while(n!=null&&n.getOpcode()<0);return n;}
    private static void origin(String name,Path expected)throws Exception{
        Class<?> c=Class.forName(name,false,ControlSettings34Probe.class.getClassLoader());
        check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected.toRealPath()),"wrong origin "+name);
    }
    private static void canceledEvent()throws Exception{
        AtomicInteger refreshes=new AtomicInteger();KeyboardInput.registerPresenceRefresh(refreshes::incrementAndGet);
        var handler=KeyboardInput.class.getDeclaredMethod("mouse",InputEvent.MouseButton.Pre.class);handler.setAccessible(true);
        for(int button=0;button<8;button++)for(int action=0;action<2;action++){
            var event=new InputEvent.MouseButton.Pre(button,action,0);event.setCanceled(true);
            handler.invoke(null,event);
            check(event.isCanceled(),"earlier cancel preserved");
            check(refreshes.get()==0,"canceled event must not refresh or acquire a new owner");
        }
        KeyboardInput.registerPresenceRefresh(()->{});
    }
    private static void mouseBytecode()throws Exception{
        var n=node(K+"KeyboardInput");var m=method(n,"mouse",null);
        int cancel=call(m,null,"isCanceled"),refresh=call(m,K+"KeyboardInput","refreshPresence");
        check(cancel>=0&&cancel<refresh,"cancel before presence refresh");
        var branch=next(m.instructions.get(cancel));check(branch instanceof JumpInsnNode&&branch.getOpcode()==IFEQ,"cancel branch shape");
        check(next(branch).getOpcode()==RETURN,"canceled event immediately returns");
        check(call(m,null,"captures")<call(m,null,"setCanceled"),"only mapped mouse can cancel");
        check(call(m,null,"key")>call(m,null,"setCanceled"),"edge only after capture");
        check(call(m,null,"clearMouse")>call(m,null,"key"),"clear world mouse after game edge");
        check(field(method(n,"install",null),"net/neoforged/bus/api/EventPriority","LOWEST",GETSTATIC),"lowest event priority");
        check(call(method(n,"clearHostKeys",null),K+"KeyboardMappingState","clearSelected")>=0,"selective mouse and keyboard clear");
        check(call(method(n,"clearMouse",null),null,"matchesMouse")>=0,"clear only matching mouse");
        check(call(method(n,"clearMouse",null),null,"stopDestroyBlock")>=0,"stop captured attack destruction");
        var mapping=node(K+"KeyboardMappingState");var clear=method(mapping,"clear",null);
        check(call(clear,null,"piq$setDown")>=0&&call(clear,null,"piq$setClicks")>=0,"clear both held and queued clicks");
    }
    private static void stateBehaviors(){
        int[][] keys=new int[12][];Arrays.setAll(keys,i->new int[]{-1});keys[0]=new int[]{-1000,75};keys[4]=new int[]{87};
        var s=new KeyboardControlState(KeyboardConfig.Preset.LEGACY,keys);
        check(s.captures(-1000,false)&&!s.captures(-1001,false),"only current functional mouse captured");
        check(!s.captures(87,true),"unlocked W remains world movement");s.activate(true,k->false,k->k==87);
        for(int i=0;i<100;i++){
            s.key(-1000,1);check(s.mask(0)==1,"mouse press native bit");
            s.key(-1000,0);check(s.mask(0)==0,"same tick mouse release");
        }
        s.key(-1000,1);s.pause();check(s.mask(1)==0,"focus/return clears mask");
        s.activate(true,k->k==-1000);check(!s.armed(),"held mouse cannot rearm");
        s.activate(true,k->false);check(s.armed()&&s.mask(0)==0,"neutral rearm has no old edge");
        s.toggle();check(s.captures(87,true),"locked W goes to device");s.activate(true,k->false,k->k==87);s.key(87,1,true);
        check(s.mask(0,k->k==87)==16,"locked direction input");s.toggle();
        check(!s.captures(87,true)&&s.mask(0)==0,"unlock releases device movement");
    }
    private static void settingsBytecode()throws Exception{
        var screen="Lnet/minecraft/client/gui/screens/Screen;";
        var gba=node("cn/piq/gba/client/GbaHandheldScreen");int keyboard=0,pad=0;
        for(var m:gba.methods)for(var i:m.instructions)if(i instanceof MethodInsnNode c){
            if(c.owner.equals(K+"ControlSettingsScreen")&&c.name.equals("<init>")){
                keyboard++;check(c.desc.equals("("+screen+"L"+K+"KeyboardConfig$Profile;Ljava/lang/String;)V"),"explicit keyboard profile overload");
                check(field(m,K+"KeyboardConfig$Profile","SFC",GETSTATIC)&&constant(m,"GBA（共享 SFC）"),"GBA keyboard shared SFC");
                check(!field(m,K+"KeyboardConfig$Profile","NES",GETSTATIC),"GBA not NES");
            }
            if(c.owner.equals(K+"GamepadInput")&&c.name.equals("settings")){
                pad++;check(c.desc.equals("("+screen+"L"+K+"GamepadInput$ProfileKind;Ljava/lang/String;)"+screen),"explicit gamepad profile overload");
                check(field(m,K+"GamepadInput$ProfileKind","SFC",GETSTATIC)&&constant(m,"GBA（共享 SFC）"),"GBA pad shared SFC");
                check(!field(m,K+"GamepadInput$ProfileKind","NES",GETSTATIC),"GBA pad not NES");
            }
        }
        check(keyboard==1&&pad==1,"exactly two GBA settings entries");
        var keyboardNode=node(K+"ControlSettingsScreen");
        check(call(method(keyboardNode,"<init>","("+screen+")V"),K+"KeyboardInput","settingsProfile")>=0,"default keyboard follows active device");
        var explicit=method(keyboardNode,"<init>","("+screen+"L"+K+"KeyboardConfig$Profile;Ljava/lang/String;)V");
        check(field(explicit,K+"ControlSettingsScreen","profile",PUTFIELD)&&!field(explicit,K+"KeyboardConfig$Profile","NES",GETSTATIC),"explicit profile stored, never replaced by NES");
        check(call(method(keyboardNode,"onClose",null),K+"KeyboardInput","save")==-1,"keyboard cancel not save");
        boolean follows=false;for(var m:keyboardNode.methods)if(call(m,K+"GamepadInput","settings")>=0)follows|=field(m,K+"ControlSettingsScreen","profile",GETFIELD)&&field(m,K+"ControlSettingsScreen","deviceLabel",GETFIELD);
        check(follows,"keyboard-to-pad retains selected profile and label");
        var gamepad=node(K+"GamepadInput");check(call(method(gamepad,"settings","("+screen+")"+screen),K+"KeyboardInput","settingsProfile")>=0,"default gamepad follows active device");
        var padNode=node(K+"GamepadSettingsScreen");
        var padCtor=method(padNode,"<init>","("+screen+"L"+K+"GamepadInput$ProfileKind;Ljava/lang/String;)V");
        check(field(padCtor,K+"GamepadSettingsScreen","kind",PUTFIELD)&&!field(padCtor,K+"GamepadInput$ProfileKind","NES",GETSTATIC),"explicit pad profile stored");
        check(call(method(padNode,"onClose",null),K+"GamepadInput","save")==-1,"pad cancel not save");
    }
    public static void main(String[] args)throws Exception{
        Path fc=Path.of(args[0]),gba=Path.of(args[1]);
        for(String n:List.of("KeyboardInput","KeyboardControlState","KeyboardMappingState","ControlSettingsScreen","GamepadInput","GamepadSettingsScreen"))origin("cn.piq.retro.client."+n,fc);
        origin("cn.piq.gba.client.GbaHandheldScreen",gba);
        canceledEvent();mouseBytecode();stateBehaviors();settingsBytecode();
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_canceled_neoforge_events\":16,\"actual_production_state\":true,\"gui_verification\":\"production-bytecode-only\",\"minecraft_started\":false,\"native_core_started\":false}");
    }
}
