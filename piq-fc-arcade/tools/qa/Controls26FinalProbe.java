import java.nio.file.*;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/** Final JAR CodeSource checks plus the real JUnit engine, including TempDir and assumptions. */
public final class Controls26FinalProbe {
    public static void main(String[] args)throws Exception{
        int origins=0;
        for(String line:Files.readAllLines(Path.of(args[0]))){
            String[] pair=line.split("\\t",2);Path expected=Path.of(pair[0]).toRealPath();
            Class<?> type=Class.forName(pair[1],false,Controls26FinalProbe.class.getClassLoader());
            Path actual=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if(!expected.equals(actual))throw new AssertionError("Not a final JAR: "+pair[1]+" -> "+actual);
            origins++;
        }
        var request=LauncherDiscoveryRequestBuilder.request();
        for(int i=1;i<args.length;i++)request.selectors(DiscoverySelectors.selectClass(args[i]));
        // These three old tests read source files; they are not final-JAR behavior evidence.
        Set<String> sourceOnly=Set.of("cn.piq.fcarcade.layout.DualCabinetGeometryTest#sessionSkinAndBodyUseTheSameRegisteredCabinet",
                "cn.piq.fcarcade.layout.CabinetVideoGeometryTest#newRendererIsScopedToAnExternalCurrentTargetAndDoesNotOwnTextureLifetime",
                "cn.piq.fcarcade.layout.ScreenSurfaceGeometryTest#actualRendererHasOneQuadPathAndCenteringRemainsSeparateExactlyOnce");
        request.filters((PostDiscoveryFilter)descriptor->{var source=descriptor.getSource().orElse(null);
            return source instanceof MethodSource method&&sourceOnly.contains(method.getClassName()+"#"+method.getMethodName())
                    ?FilterResult.excluded("source-only contract remains in root full build"):FilterResult.included("final JAR behavior");});
        var listener=new SummaryGeneratingListener();var aborted=new ArrayList<String>();var skipped=new ArrayList<String>();
        var detail=new TestExecutionListener(){
            @Override public void executionFinished(TestIdentifier test,TestExecutionResult result){if(test.isTest()&&result.getStatus()==TestExecutionResult.Status.ABORTED)aborted.add(test.getDisplayName());}
            @Override public void executionSkipped(TestIdentifier test,String reason){if(test.isTest())skipped.add(test.getDisplayName());}
        };
        var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener,detail);launcher.execute(request.build());
        var summary=listener.getSummary();
        if(summary.getTestsFoundCount()==0||summary.getTestsFailedCount()!=0||summary.getContainersFailedCount()!=0){
            summary.printTo(new java.io.PrintWriter(System.err,true));summary.printFailuresTo(new java.io.PrintWriter(System.err,true));throw new AssertionError("Final JAR tests failed");
        }
        System.out.println("{\"ok\":true,\"production_origin\":\"final-jar-only\",\"origin_classes\":"+origins+
                ",\"tests_found\":"+summary.getTestsFoundCount()+",\"tests_succeeded\":"+summary.getTestsSucceededCount()+
                ",\"tests_aborted\":"+summary.getTestsAbortedCount()+",\"tests_skipped\":"+summary.getTestsSkippedCount()+
                ",\"aborted_names\":"+json(aborted)+",\"skipped_names\":"+json(skipped)+
                ",\"tests_failed\":0,\"production_compiled\":false,\"minecraft_or_native_core_started\":false}");
    }
    private static String json(List<String> values){return "["+String.join(",",values.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList())+"]";}
}

