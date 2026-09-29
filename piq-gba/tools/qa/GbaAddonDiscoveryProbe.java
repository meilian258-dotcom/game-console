import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import com.google.gson.Gson;
import org.objectweb.asm.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** Actual FML metadata + annotation discovery, without executing either mod entry point. */
public final class GbaAddonDiscoveryProbe {
    private static int assertions;
    static void check(boolean result,String detail){assertions++;if(!result)throw new AssertionError(detail);}
    static boolean compatible(Collection<IModInfo> mods){
        Map<String,String> versions=new HashMap<>(Map.of("minecraft","1.21.1","neoforge","21.1.236"));
        for(var mod:mods)if(versions.putIfAbsent(mod.getModId(),mod.getVersion().toString())!=null)return false;
        for(var mod:mods)for(var dep:mod.getDependencies())if(dep.getType()==IModInfo.DependencyType.REQUIRED){
            String found=versions.get(dep.getModId());if(found==null||!dep.getVersionRange().containsVersion(new DefaultArtifactVersion(found)))return false;
        }return true;
    }
    public static void main(String[] args)throws Exception {
        check(args.length==2,"FC and GBA final JARs required");
        List<JarContents> contents=new ArrayList<>();List<ModFile> files=new ArrayList<>();List<List<IModInfo>> groups=new ArrayList<>();List<Map<String,Object>> details=new ArrayList<>();
        try{
            for(String arg:args){
                Path path=Path.of(arg).toRealPath();var content=JarContents.of(path);contents.add(content);
                var parsed=new JarModsDotTomlModFileReader().read(content,ModFileDiscoveryAttributes.DEFAULT);
                check(parsed instanceof ModFile,"actual FML mod reader");var file=(ModFile)parsed;files.add(file);
                check(file.identifyMods(),"actual identifyMods");check(file.getFilePath().toRealPath().equals(path),"exact supplied archive");
                var mods=List.copyOf(file.getModInfos());groups.add(mods);var scan=file.compileContent();Set<String> entry=new TreeSet<>();
                for(var a:scan.getAnnotations())if(a.annotationType().getClassName().equals("net.neoforged.fml.common.Mod"))entry.add(String.valueOf(a.annotationData().get("value")));
                Set<String> ids=new TreeSet<>();for(var mod:mods)ids.add(mod.getModId());check(entry.equals(ids),"matching declared and scanned entry points");
                details.add(Map.of("jar",path.toString(),"mod_ids",ids,"entry_points",entry,"scanned_classes",scan.getClasses().size()));
            }
            check(groups.get(0).size()==1&&groups.get(0).getFirst().getModId().equals("piq_fc_arcade"),"FC identity");
            check(groups.get(1).size()==1&&groups.get(1).getFirst().getModId().equals("piq_gba"),"single new addon identity");
            check(groups.get(1).getFirst().getVersion().toString().equals("0.1.0-alpha.2"),"exact server-preview version");
            check(compatible(groups.get(0)),"FC dependencies complete");check(!compatible(groups.get(1)),"GBA without FC rejected");
            List<IModInfo> all=new ArrayList<>(groups.get(0));all.addAll(groups.get(1));check(compatible(all),"actual Maven dependency ranges accept supplied FC");
            all.add(groups.get(1).getFirst());check(!compatible(all),"duplicate addon rejected");
            Path addonPath=Path.of(args[1]).toRealPath();
            check(Path.of(cn.piq.gba.GbaMod.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(addonPath),"register actual final addon");
            cn.piq.gba.GbaMod.registerBackend();
            var declared=cn.piq.fcarcade.cabinet.CabinetBackends.find(cn.piq.gba.GbaMod.BACKEND);
            check(declared!=null&&!declared.localOnly(),"actual FC registry receives server-capable GBA declaration");
            check(cn.piq.fcarcade.cabinet.CabinetBackends.maxPlayers(cn.piq.gba.GbaMod.BACKEND)==1,"GBA declares exactly one server control seat, not fake P2");
            check(cn.piq.fcarcade.cabinet.CabinetBackends.find(cn.piq.fcarcade.cabinet.CabinetBackends.NES)!=null,"builtin NES declaration retained");
            try{cn.piq.gba.GbaMod.registerBackend();throw new AssertionError("duplicate registration accepted");}catch(IllegalArgumentException expected){assertions++;}
            try(var z=new ZipFile(args[1])){
                check(z.getEntry("cn/piq/gba/bridge/GbaCore.class")==null,"native JNA core absent from Minecraft addon");
                check(z.getEntry("cn/piq/gba/bridge/GbaWorker.class")==null,"native worker absent from Minecraft addon");
                byte[] common=z.getInputStream(z.getEntry("cn/piq/gba/GbaMod.class")).readAllBytes();
                new ClassReader(common).accept(new ClassVisitor(Opcodes.ASM9){
                    @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){check(!owner.startsWith("net/minecraft/client/")&&!owner.startsWith("cn/piq/gba/client/")&&!owner.startsWith("com/sun/jna/"),"common method does not load client/native");}
                        @Override public void visitTypeInsn(int opcode,String type){check(!type.startsWith("net/minecraft/client/")&&!type.startsWith("cn/piq/gba/client/")&&!type.startsWith("com/sun/jna/"),"common allocation is side safe");}
                    };}
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            }
            System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"production_origin","final-jar-only","actual_fml_reader",true,"actual_annotation_scan",true,"actual_common_registration",true,"dependency_check","Actual FML ModInfo and Maven VersionRange; not full bootstrap","mod_entry_points_executed",false,"minecraft_or_native_core_started",false,"discovery",details)));
        }finally{for(var file:files)file.getSecureJar().close();for(var content:contents)content.close();}
    }
}
