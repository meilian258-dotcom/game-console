import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public class SfcServerTestRunner {
    public static void main(String[]args){var request=LauncherDiscoveryRequestBuilder.request().selectors(
        selectClass("cn.piq.sfchome.server.SfcInputTimelineTest"),selectClass("cn.piq.sfchome.server.SfcFrameClockTest"),
        selectClass("cn.piq.sfchome.server.SfcTransferBudgetTest"),selectClass("cn.piq.sfchome.server.SfcInputHealthTest"),selectClass("cn.piq.sfchome.server.SfcServerSourceContractTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=35||listener.getSummary().getTestsFailedCount()!=0)throw new AssertionError("SFC server regressions failed");}
}
