package cn.piq.fcarcade.client.rom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Compiles this probe only; all library/layout classes must originate in the final JAR. */
public final class Alpha16PickerProbe {
    private static int assertions, scenarios;
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("Packaged picker check " + assertions);
    }
    private static void origin(Class<?> type, Path jar) throws Exception {
        check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));
    }
    private static boolean contains(LocalRomPickerLayout.Rect outer, LocalRomPickerLayout.Rect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y() && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }
    private static boolean overlaps(LocalRomPickerLayout.Rect a, LocalRomPickerLayout.Rect b) {
        return a.x() < b.right() && a.right() > b.x() && a.y() < b.bottom() && a.bottom() > b.y();
    }
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toRealPath();
        origin(LocalRomPickerLayout.class, jar);
        origin(LocalRomPickerLayout.Layout.class, jar);
        origin(LocalRomPickerLayout.Rect.class, jar);
        origin(LocalRomLibrary.class, jar);
        origin(LocalRomLibrary.Entry.class, jar);
        origin(LocalRomLibrary.Scan.class, jar);
        for (int width : new int[]{320,360,480,512,640,800,1024,1280,1920})
            for (int height : new int[]{240,256,278,320,360,480,556,1080})
                for (int count : new int[]{0,1,2,5,16,40,512})
                    for (int page : new int[]{-1,0,1,5,999}) {
                        scenarios++;
                        var layout = LocalRomPickerLayout.create(width,height,count,page);
                        check(layout.supported());
                        check(contains(new LocalRomPickerLayout.Rect(0,0,width,height),layout.panel()));
                        check(layout.rows() > 0);
                        check(layout.pages() == Math.max(1,(count+layout.rows()-1)/layout.rows()));
                        check(layout.page() >= 0 && layout.page() < layout.pages());
                        check(layout.start() == layout.page()*layout.rows());
                        var sections = List.of(layout.toolbar(),layout.search(),layout.list(),layout.footer());
                        for (int i=0;i<sections.size();i++) {
                            check(contains(layout.panel(),sections.get(i)));
                            for (int j=0;j<i;j++) check(!overlaps(sections.get(i),sections.get(j)));
                        }
                        for (int row=0;row<layout.rows();row++) {
                            check(contains(layout.list(),layout.row(row)));
                            check(layout.row(row).height()==20);
                            if(row>0) check(!overlaps(layout.row(row-1),layout.row(row)));
                        }
                        check(layout.footer().y()+59 <= layout.panel().bottom());
                    }
        for (int[] size : new int[][]{{1,1},{319,240},{320,239},{200,120}})
            check(!LocalRomPickerLayout.create(size[0],size[1],1,0).supported());

        Path temporary = Files.createTempDirectory("piq-alpha16-picker-probe-").toRealPath();
        int skippedLinkChecks=0;
        try {
            Path directory=temporary.resolve("sfc");
            check(!Files.exists(directory));
            check(LocalRomLibrary.prepare(directory).equals(directory));
            check(LocalRomLibrary.scan(directory,Set.of(".sfc",".smc"),Set.of()).entries().isEmpty());
            Files.write(directory.resolve("中文.sfc"),new byte[]{1,2,3});
            Files.write(directory.resolve("A.SMC"),new byte[]{4});
            Files.write(directory.resolve("z.sfc"),new byte[]{5,6});
            Files.write(directory.resolve("notes.txt"),new byte[]{7});
            Files.createDirectory(directory.resolve("directory.sfc"));
            var scan=LocalRomLibrary.scan(directory,Set.of("sfc","SMC"),Set.of("z.sfc"));
            check(scan.entries().size()==2);
            check(scan.entries().get(0).fileName().equals("A.SMC"));
            check(scan.entries().get(1).fileName().equals("中文.sfc"));
            check(scan.entries().get(1).bytes()==3);
            check(scan.skipped()==3 && !scan.limited());
            check(Files.size(directory.resolve("中文.sfc"))==3);
            try { scan.entries().clear(); throw new AssertionError("Mutable result"); }
            catch(UnsupportedOperationException expected) { assertions++; }
            LocalRomLibrary.validateFile(directory.resolve("中文.sfc"));
            try { LocalRomLibrary.validateFile(directory); throw new AssertionError("Directory accepted as file"); }
            catch(IOException expected) { assertions++; }
            for (Set<String> extensions : List.of(Set.<String>of(),Set.of("../sfc"),Set.of(".sfc/"))) {
                try { LocalRomLibrary.scan(directory,extensions,Set.of()); throw new AssertionError("Invalid suffix accepted"); }
                catch(IOException expected) { assertions++; }
            }
            try { LocalRomLibrary.scan(directory,Set.of(".sfc"),Set.of("../file")); throw new AssertionError("Invalid excluded name"); }
            catch(IOException expected) { assertions++; }
            Path fileParent=temporary.resolve("existing-file");Files.write(fileParent,new byte[]{99});
            try { LocalRomLibrary.prepare(fileParent.resolve("child")); throw new AssertionError("File replaced as directory"); }
            catch(IOException expected) { assertions++; }
            check(Files.readAllBytes(fileParent)[0]==99);
            Path large=LocalRomLibrary.prepare(temporary.resolve("limited"));
            for(int i=0;i<513;i++)Files.write(large.resolve(String.format("%04d.zip",i)),new byte[]{0});
            var limited=LocalRomLibrary.scan(large,Set.of(".zip"),Set.of());
            check(limited.entries().size()<=512 && limited.limited());
            Path nativeFolder=LocalRomLibrary.arcadeDirectory(temporary);
            Path sfcFolder=LocalRomLibrary.sfcDirectory(temporary);
            check(nativeFolder.equals(temporary.resolve("piq-native-arcade/roms")));
            check(sfcFolder.equals(temporary.resolve("piq-sfc-home/roms")));
            check(!Files.exists(nativeFolder) && !Files.exists(sfcFolder));
            Thread.currentThread().interrupt();
            try { LocalRomLibrary.scan(directory,Set.of(".sfc"),Set.of()); throw new AssertionError("Interrupt ignored"); }
            catch(java.io.InterruptedIOException expected) { assertions++; }
            finally { Thread.interrupted(); }
            Path link=temporary.resolve("linked-directory");
            try { Files.createSymbolicLink(link,directory); }
            catch(IOException|UnsupportedOperationException|SecurityException unavailable) { skippedLinkChecks++; }
            if(Files.isSymbolicLink(link)) {
                try { LocalRomLibrary.scan(link,Set.of(".sfc"),Set.of()); throw new AssertionError("Linked directory accepted"); }
                catch(IOException expected) { assertions++; }
                try { LocalRomLibrary.validateFile(link.resolve("中文.sfc")); throw new AssertionError("Linked parent accepted"); }
                catch(IOException expected) { assertions++; }
            }
        } finally {
            // Only this freshly-created, resolved temporary tree. Files.walk does not follow links.
            try(var paths=Files.walk(temporary)) {
                for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if(!path.toAbsolutePath().normalize().startsWith(temporary))throw new IOException("Probe cleanup scope changed");
                    Files.delete(path);
                }
            }
        }
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"layout_scenarios\":"+scenarios
                +",\"link_creation_unavailable\":"+skippedLinkChecks
                +",\"production_origin\":\"final-jar-only\",\"minecraft_or_native_core_started\":false}");
    }
}
