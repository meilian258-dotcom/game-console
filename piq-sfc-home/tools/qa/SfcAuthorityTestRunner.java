import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class SfcAuthorityTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.sfchome.server.SfcControllerAuthorityTest"),
            selectClass("cn.piq.sfchome.server.SfcJoinGateTest"),
            selectClass("cn.piq.sfchome.server.SfcInputHealthTest"),
            selectClass("cn.piq.sfchome.server.SfcInputTimelineTest"),
            selectClass("cn.piq.sfchome.server.SfcGamepadNetworkRegressionTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=45||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("Server controller-authority behavior regression failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsFoundCount()+",\"minecraft_or_native_core_started\":false}");
    }
}
