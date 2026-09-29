package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId.MAME;
import static org.junit.jupiter.api.Assertions.*;

/** Only tiny inert files in temporary directories; never an executable installation. */
class NativeVersionedHelperInstallTest {
    @TempDir Path root;
    private static final String OLD="piq-native-arcade/runtime/piq-native-helper.jar";
    private static final String NEW="piq-native-arcade/runtime/piq-native-helper-v4.jar";
    private static final byte[] BYTES="inert v4 helper fixture, not executable".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private RuntimeInstaller fixture()throws Exception {
        var artifact=new RuntimeCatalog.Artifact(NEW,BYTES.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)));
        var archive=root.resolve("inert-native011.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("native-runtime/win-x64-v1/"+NEW));zip.write(BYTES);zip.closeEntry();}
        return new RuntimeInstaller(root,List.of(new RuntimeCatalog.Component(MAME,"fixture",List.of(artifact))),true,Map.of(MAME,RuntimeInstaller.bundledArchive(archive)));
    }
    @Test void versionedAdditionPreservesOldHelperAndUnrelatedFiles()throws Exception {
        Path old=root.resolve(OLD);Files.createDirectories(old.getParent());Files.writeString(old,"keep old helper");
        Path unrelated=old.resolveSibling("unknown-helper.jar");Files.writeString(unrelated,"keep unknown file");
        Object identity=Files.getAttribute(old,"basic:fileKey");
        var installer=fixture();var result=installer.install(Set.of(MAME),()->false,p->{});
        assertEquals(RuntimeInstaller.Outcome.INSTALLED,result.outcome(),result.details().toString());assertEquals(1,result.installed());
        assertEquals("keep old helper",Files.readString(old));assertEquals(identity,Files.getAttribute(old,"basic:fileKey"));
        assertEquals("keep unknown file",Files.readString(unrelated));assertArrayEquals(BYTES,Files.readAllBytes(root.resolve(NEW)));
        assertEquals(RuntimeInstaller.Outcome.READY,installer.install(Set.of(MAME),()->false,p->{}).outcome());
    }
    @Test void unknownContentAtTheVersionedDestinationIsNeverOverwritten()throws Exception {
        var target=root.resolve(NEW);Files.createDirectories(target.getParent());Files.writeString(target,"foreign content");
        Object identity=Files.getAttribute(target,"basic:fileKey");
        var result=fixture().install(Set.of(MAME),()->false,p->{});
        assertEquals(RuntimeInstaller.Outcome.BLOCKED,result.outcome());assertEquals(0,result.installed());
        assertEquals("foreign content",Files.readString(target));assertEquals(identity,Files.getAttribute(target,"basic:fileKey"));
    }
    public static void main(String[] args)throws Exception {
        int count=0;
        for(var method:NativeVersionedHelperInstallTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){
            var instance=new NativeVersionedHelperInstallTest();instance.root=Files.createTempDirectory("native011-inert-installer-");
            try{method.invoke(instance);count++;}finally{try(var paths=Files.walk(instance.root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
        }
        System.out.println("Versioned helper real installer inert tests passed: "+count);
    }
}
