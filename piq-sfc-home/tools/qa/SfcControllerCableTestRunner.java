import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class SfcControllerCableTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.sfchome.server.SfcControllerAuthorityTest"),
            selectClass("cn.piq.sfchome.server.SfcControllerInventoryTest"),
            selectClass("cn.piq.sfchome.server.SfcControllerCableTest"),
            selectClass("cn.piq.sfchome.server.SfcApplianceSeparationSourceTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=35||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0||result.getTestsSkippedCount()!=0)
            throw new AssertionError("SFC cable-range/revocation regression failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsFoundCount()+",\"minecraft_or_native_core_started\":false}");
    }
}
