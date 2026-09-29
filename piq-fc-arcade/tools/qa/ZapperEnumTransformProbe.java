import com.google.gson.*;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender;
import net.neoforged.neoforgespi.language.IModInfo;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Actual FML parser + enum transformer on real MC bytecode; never defines the MC enum. */
public final class ZapperEnumTransformProbe {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Set<String> fields(ClassNode node){var values=new LinkedHashSet<String>();for(var f:node.fields)if((f.access&Opcodes.ACC_ENUM)!=0)values.add(f.name);return values;}
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("enumextensions.json actual-ArmPose.class transformed.class");
        var document=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject().getAsJsonArray("entries");
        check(document.size()==3,"Exactly old two poses plus gun");
        IModInfo owner=(IModInfo)Proxy.newProxyInstance(ZapperEnumTransformProbe.class.getClassLoader(),new Class<?>[]{IModInfo.class},(proxy,method,values)->switch(method.getName()) {
            case "getModId"->"piq_fc_arcade";case "toString"->"Actual-resource metadata owner piq_fc_arcade";
            case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==values[0];
            default->throw new UnsupportedOperationException("Unexpected metadata access: "+method.getName());
        });
        RuntimeEnumExtender.loadEnumPrototypes(Map.of(owner,Path.of(args[0])));
        var node=new ClassNode();new ClassReader(Files.readAllBytes(Path.of(args[1]))).accept(node,0);
        check(node.name.equals("net/minecraft/client/model/HumanoidModel$ArmPose"),"Real patched MC enum");
        Set<String> old=fields(node);var extender=new RuntimeEnumExtender();var type=Type.getObjectType(node.name);
        var phases=extender.handlesClass(type,false);check(!phases.isEmpty(),"FML registered real resource target");
        check(extender.processClass(phases.iterator().next(),node,type),"Actual FML processClass transformed target");
        var added=new LinkedHashSet<>(fields(node));added.removeAll(old);
        check(added.equals(Set.of("PIQ_FC_ARCADE_CONTROLLER_TWO_HANDS","PIQ_FC_ARCADE_CONTROLLER_SINGLE_HAND","PIQ_FC_ARCADE_ZAPPER")),"Only three precise extensions");
        check(fields(node).containsAll(old),"All vanilla enum constants retained");
        int gunProxy=0,returnProxy=0;
        for(var m:node.methods)for(var insn:m.instructions) {
            if(insn instanceof FieldInsnNode f&&f.owner.equals("cn/piq/fcarcade/client/zapper/ZapperArmPoseParameters")&&f.name.equals("AIM")&&f.getOpcode()==Opcodes.GETSTATIC)gunProxy++;
            if(insn instanceof MethodInsnNode call&&call.owner.equals("net/neoforged/fml/common/asm/enumextension/EnumProxy")&&call.name.equals("setValue"))returnProxy++;
        }
        check(gunProxy>0,"Generated initialization actually reads gun AIM EnumProxy");
        check(returnProxy==3,"FML returns all constructed poses to exact proxies");
        var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);Files.write(Path.of(args[2]),writer.toByteArray(),StandardOpenOption.CREATE_NEW);
        System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"actual_fml_enum_transform",true,
                "new_enum_fields",added,"vanilla_constants_preserved",old.size(),"minecraft_started",false,
                "target_class_defined",false,"mod_owner","piq_fc_arcade","limits","Actual parser/transformer only; no enum initialization, ModLauncher game boot or renderer execution")));
    }
}
