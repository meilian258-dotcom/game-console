import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class SfcGamepadNetworkTestRunner {
    public static void main(String[] args) {
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.sfchome.server.SfcJoinGateTest"),
                selectClass("cn.piq.sfchome.server.SfcInputHealthTest"),
                selectClass("cn.piq.sfchome.server.SfcInputTimelineTest"),
                selectClass("cn.piq.sfchome.server.SfcGamepadNetworkRegressionTest"),
                selectClass("cn.piq.sfchome.client.SfcInputSendPolicyTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));
        result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=41||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("SFC gamepad/network regression failed");
        System.out.println("{\"ok\":true,\"tests\":41,\"minecraft_or_native_core_started\":false}");
    }
}
