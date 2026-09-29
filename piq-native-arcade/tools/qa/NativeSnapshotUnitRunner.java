import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class NativeSnapshotUnitRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.nativearcade.bridge.NativeSnapshotStateTest"),
            selectClass("cn.piq.nativearcade.bridge.NativeSnapshotWorkspaceTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printFailuresTo(new java.io.PrintWriter(System.err,true));
        if(result.getTestsFoundCount()!=18||result.getTestsSucceededCount()!=18||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0||result.getTestsSkippedCount()!=0)throw new AssertionError("Native snapshot unit test failure/count");
        System.out.println("{\"ok\":true,\"tests\":18,\"passed\":18,\"skipped\":0,\"real_junit\":true,\"native_core_started\":false}");
    }
}
