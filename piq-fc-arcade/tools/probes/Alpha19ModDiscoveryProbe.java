import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.*;

/** Real FML metadata parser + annotation scanner only. Never executes a mod entry point. */
public final class Alpha19ModDiscoveryProbe {
    private static int assertions;
    private static void check(boolean result,String detail) { assertions++;if(!result)throw new AssertionError(detail); }
    private static boolean compatible(Collection<IModInfo> mods) {
        Map<String,String> versions=new HashMap<>(Map.of("minecraft","1.21.1","neoforge","21.1.236"));
        for(var mod:mods)if(versions.putIfAbsent(mod.getModId(),mod.getVersion().toString())!=null)return false;
        for(var mod:mods)for(var dep:mod.getDependencies())if(dep.getType()==IModInfo.DependencyType.REQUIRED) {
            var found=versions.get(dep.getModId());
            if(found==null||!dep.getVersionRange().containsVersion(new DefaultArtifactVersion(found)))return false;
        }
        return true;
    }
    public static void main(String[] args) throws Exception {
        check(args.length==3,"Expected FC, combined SFC and Native JARs");
        List<JarContents> open=new ArrayList<>();List<ModFile> files=new ArrayList<>();
        List<List<IModInfo>> groups=new ArrayList<>();List<Map<String,Object>> discovery=new ArrayList<>();
        try {
            for(String arg:args) {
                Path path=Path.of(arg).toRealPath();var content=JarContents.of(path);open.add(content);
                var found=new JarModsDotTomlModFileReader().read(content,ModFileDiscoveryAttributes.DEFAULT);
                check(found instanceof ModFile,"Actual FML reader found a mod file: "+path);
                ModFile file=(ModFile)found;files.add(file);
                check(file.identifyMods(),"Actual FML identifyMods succeeded");
                check(file.getFilePath().toRealPath().equals(path),"FML parsed the exact delivered JAR");
                List<IModInfo> mods=List.copyOf(file.getModInfos());groups.add(mods);
                var scan=file.compileContent();Set<String> entryPoints=new TreeSet<>();
                for(var annotation:scan.getAnnotations())if(annotation.annotationType().getClassName().equals("net.neoforged.fml.common.Mod"))
                    entryPoints.add(String.valueOf(annotation.annotationData().get("value")));
                Set<String> ids=new TreeSet<>();List<Map<String,Object>> declared=new ArrayList<>();
                for(var mod:mods) {
                    ids.add(mod.getModId());check(entryPoints.contains(mod.getModId()),"FML annotation scan found @Mod for "+mod.getModId());
                    List<Map<String,String>> dependencies=new ArrayList<>();
                    for(var dep:mod.getDependencies())dependencies.add(Map.of("id",dep.getModId(),"range",dep.getVersionRange().toString(),"type",dep.getType().name(),"side",dep.getSide().name(),"ordering",dep.getOrdering().name()));
                    declared.add(Map.of("id",mod.getModId(),"version",mod.getVersion().toString(),"dependencies",dependencies));
                }
                check(ids.equals(entryPoints),"No undeclared or missing @Mod entry point");
                discovery.add(Map.of("jar",path.toString(),"module_name",file.getSecureJar().name(),"mods",declared,
                        "scanned_classes",scan.getClasses().size(),"entry_points",entryPoints,"access_transformers",file.getAccessTransformers().size()));
            }
            check(groups.get(0).stream().map(IModInfo::getModId).toList().equals(List.of("piq_fc_arcade")),"FC owns only main mod ID");
            check(new HashSet<>(groups.get(1).stream().map(IModInfo::getModId).toList()).equals(Set.of("piq_sfc_arcade","piq_sfc_home")),"One SFC JAR discovers both legacy mod IDs");
            check(groups.get(2).stream().map(IModInfo::getModId).toList().equals(List.of("piq_native_arcade")),"Native mod identity");
            check(compatible(groups.get(0)),"FC alone dependency graph is complete");
            check(!compatible(groups.get(1)),"SFC without FC is rejected");
            check(!compatible(groups.get(2)),"Native without FC is rejected");
            List<IModInfo> fcSfc=new ArrayList<>(groups.get(0));fcSfc.addAll(groups.get(1));
            List<IModInfo> fcNative=new ArrayList<>(groups.get(0));fcNative.addAll(groups.get(2));
            List<IModInfo> all=new ArrayList<>(fcSfc);all.addAll(groups.get(2));
            check(compatible(fcSfc),"FC and combined SFC dependencies match actual Maven ranges");
            check(compatible(fcNative),"FC and Native dependencies match actual Maven ranges");
            check(compatible(all),"All current mods dependencies match");
            var duplicate=new ArrayList<>(all);duplicate.add(groups.get(1).get(0));
            check(!compatible(duplicate),"Duplicate legacy core ID alongside combined jar is rejected");
            check(files.get(1).getAccessTransformers().size()==1,"Combined SFC retains exactly one own access transformer");
            System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"loader","FancyModLoader 4.0.43",
                    "level","actual JarModsDotTomlModFileReader + ModFile.identifyMods + ASM compileContent",
                    "dependency_check","Actual FML ModInfo + Maven VersionRange; not full FML ModSorter/bootstrap",
                    "production_origin","final-jar-only","discovery",discovery,"minecraft_or_native_core_started",false,"mod_entry_points_executed",false)));
        } finally {
            for(var file:files)file.getSecureJar().close();
            for(var content:open)content.close();
        }
    }
}
