package cn.piq.replaycompat;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import net.neoforged.neoforgespi.language.MavenVersionAdapter;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

/** Uses the packaged TOML and NeoForge's real Maven range parser, never a lexical comparison. */
public final class PackagedMetadataTest {
    private static int checks;
    private static void check(boolean condition,String detail){checks++;if(!condition)throw new AssertionError(detail);}
    public static void main(String[] args)throws Exception {
        String expected=System.getProperty("piq.replay.test.version");
        try(var jar=new JarFile(Path.of(System.getProperty("piq.replay.test.jar")).toFile())){
            var entry=jar.getJarEntry("META-INF/neoforge.mods.toml");check(entry!=null,"packaged metadata missing");
            var metadata=new TomlParser().parse(new InputStreamReader(jar.getInputStream(entry),StandardCharsets.UTF_8));
            List<UnmodifiableConfig> mods=metadata.get("mods");
            check(mods.size()==1,"unexpected mod declarations");
            check("piq_replay_compat".equals(mods.getFirst().get("modId")),"wrong packaged mod ID");
            check(expected.equals(mods.getFirst().get("version")),"source/package version mismatch");
            check(expected.equals(jar.getManifest().getMainAttributes().getValue("Implementation-Version")),"manifest/package version mismatch");
            List<UnmodifiableConfig> dependencies=metadata.get("dependencies.piq_replay_compat");
            var fc=dependencies.stream().filter(c->"piq_fc_arcade".equals(c.get("modId"))).toList();
            check(fc.size()==1,"must have exactly one FC dependency");
            check("required".equals(fc.getFirst().get("type")),"FC dependency must remain required");
            check("CLIENT".equals(fc.getFirst().get("side")),"FC dependency side changed");
            String spec=fc.getFirst().get("versionRange");
            check("[0.31.0-alpha.76.53,)".equals(spec),"public API baseline changed");
            var range=MavenVersionAdapter.createFromVersionSpec(spec);
            for(int accepted:new int[]{53,54,55,100})
                check(range.containsVersion(new DefaultArtifactVersion("0.31.0-alpha.76."+accepted)),"FC76."+accepted+" must be admitted");
            for(int rejected:new int[]{0,45,46,49,50,51,52})
                check(!range.containsVersion(new DefaultArtifactVersion("0.31.0-alpha.76."+rejected)),"FC76."+rejected+" must be rejected");
            check(range.containsVersion(new DefaultArtifactVersion("0.32.0")),"mod version is not the public API compatibility key");
            check(range.getRestrictions().size()==1,"range must not contain extra unions");
            var boundary=range.getRestrictions().getFirst();
            check(boundary.isLowerBoundInclusive()&&!boundary.isUpperBoundInclusive(),"inclusive lower/exclusive upper required");
            check(boundary.getLowerBound()!=null&&boundary.getUpperBound()==null,"public API gate replaces mod upper bound");
            var j2me=dependencies.stream().filter(c->"piq_j2me_arcade".equals(c.get("modId"))).toList();
            check(j2me.size()==1&&"incompatible".equals(j2me.getFirst().get("type")),"unverified Java ME must stay incompatible");
            var pinned=java.util.Map.of("piq_sfc_home","0.1.0-alpha.53","piq_md_home","0.1.0-alpha.16",
                    "piq_native_arcade","0.1.5.8","piq_gba","0.1.0-alpha.15",
                    "piq_computer","0.1.0-prototype.12","piq_pvz","0.1.0-prototype.12");
            for(var addon:pinned.entrySet()) {
                var matches=dependencies.stream().filter(c->addon.getKey().equals(c.get("modId"))).toList();
                check(matches.size()==1,"legacy adapter dependency missing: "+addon.getKey());
                check("optional".equals(matches.getFirst().get("type")),"legacy addon must not become required");
                check(("["+addon.getValue()+"]").equals(matches.getFirst().get("versionRange")),"legacy adapter must stay bounded");
            }
            check(jar.stream().noneMatch(e->e.getName().startsWith("cn/piq/retro/")||e.getName().startsWith("cn/piq/fcarcade/")),"main-owned API duplicated in optional JAR");
            check(jar.stream().noneMatch(e->e.getName().equals("cn/piq/replaycompat/PackagedMetadataTest.class")),"test class leaked into player JAR");
        }
        System.out.println("PackagedMetadataTest: "+checks+" checks; actual packaged TOML + NeoForge Maven range; public API required from FC53, legacy stays on test5");
    }
}
