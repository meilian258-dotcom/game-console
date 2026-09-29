import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Binds executed pure transactions to real compiled MC wiring; no Minecraft is booted. */
public final class FcPermissionRepairWiringProbe {
    private static int assertions;
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static ClassNode read(Path origin,String name)throws Exception{
        byte[] bytes;if(Files.isDirectory(origin))bytes=Files.readAllBytes(origin.resolve(name+".class"));
        else try(var jar=new JarFile(origin.toFile())){bytes=jar.getInputStream(jar.getJarEntry(name+".class")).readAllBytes();}
        var node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;
    }
    private static List<MethodInsnNode> calls(MethodNode method){var result=new ArrayList<MethodInsnNode>();for(var i:method.instructions)if(i instanceof MethodInsnNode call)result.add(call);return result;}
    private static boolean calls(MethodNode m,String name){return calls(m).stream().anyMatch(c->c.name.equals(name));}
    private static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception{
        Path origin=Path.of(args[0]);String root="cn/piq/fcarcade/";
        var server=read(origin,root+"server/ServerArcadeSessions$Manager");
        for(String name:List.of("decideResume","handleSaveSlotAction","deleteSave")){
            var wrapper=method(server,name);check(calls(wrapper,"legacyTransaction"),name+" missing permission transaction");
            check(!calls(wrapper,"delete")&&!calls(wrapper,"deleteByStorageId"),name+" deletes before guard");
            var body=method(server,name+"Permitted");check((body.access&Opcodes.ACC_PRIVATE)!=0,name+" unguarded body exposed");
            for(var m:server.methods)for(var call:calls(m))if(call.owner.equals(server.name)&&call.name.equals(body.name))check(m.name.startsWith("lambda$"+name+"$"),name+" body called outside guarded closure");
        }
        check(server.methods.stream().filter(m->m.name.equals("join")).anyMatch(m->calls(m,"legacyTransaction")),"join missing transaction");
        var join=method(server,"joinPermitted");check((join.access&Opcodes.ACC_PRIVATE)!=0,"join body exposed");
        for(var m:server.methods)for(var call:calls(m))if(call.owner.equals(server.name)&&call.name.equals("joinPermitted"))check(m.name.startsWith("lambda$join$"),"unguarded join call");
        var access=method(server,"legacyTransaction");check(calls(access,"run"),"missing transaction implementation");
        var permission=method(server,"legacyPermission");for(String name:List.of("post","isCanceled","getUseBlock","getUseItem"))check(calls(permission,name),"missing permission "+name);
        var factCalls=server.methods.stream().filter(m->m.name.startsWith("lambda$legacyTransaction$")).flatMap(m->calls(m).stream()).map(c->c.name).toList();
        for(String name:List.of("current","isAlive","isSpectator","serverLevel","getConnection","hasChunkAt","getBlockState","getBlockEntity","legacyIdentity","validStructure","selectedRom","epoch"))check(factCalls.contains(name),"missing post callback identity "+name);
        var migrate=method(server,"migratePlayerSavesToGlobalSlots");check(calls(migrate).stream().anyMatch(c->c.name.equals("migrateLegacy")&&Type.getArgumentTypes(c.desc).length==5),"runtime must pass target reservation predicate");
        var home=read(origin,root+"home/HomeHardware");var cable=home.methods.stream().filter(m->m.name.equals("useCable")&&Type.getArgumentTypes(m.desc).length==4).findFirst().orElseThrow();
        check(calls(cable,"run"),"AV entry missing transaction");check(!calls(cable,"shrink")&&!calls(cable,"connect"),"AV mutation before transaction");
        var cableBody=method(home,"useCablePermitted");check((cableBody.access&Opcodes.ACC_PRIVATE)!=0,"AV body exposed");
        check(!calls(cableBody,"allowAnchorInteraction"),"extra event after final revalidation");
        var cableCallbacks=home.methods.stream().filter(m->m.name.startsWith("lambda$useCable$")).toList();
        check(cableCallbacks.stream().flatMap(m->calls(m).stream()).filter(c->c.name.equals("cablePermission")).count()==3,"need clicked/anchor/first permissions");
        var cableFacts=cableCallbacks.stream().flatMap(m->calls(m).stream()).map(c->c.name).toList();
        for(String name:List.of("currentCablePlayer","getConnection","getItemInHand","matches","sameCableEndpoint"))check(cableFacts.contains(name),"missing AV identity "+name);
        var other=method(home,"sameCableEndpoint");for(String name:List.of("getBlockEntity","endpoint","linkId","isRemoved"))check(calls(other,name),"missing endpoint identity "+name);
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_compiled_mc_wiring\":true,\"minecraft_started\":false,\"event_runtime_executed\":false}");
    }
}
