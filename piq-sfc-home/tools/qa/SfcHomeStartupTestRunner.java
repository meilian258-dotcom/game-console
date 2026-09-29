import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcHomeStartupTestRunner {
    public static void main(String[]args){var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.sfchome.server.SfcHomeStartPolicyTest"),selectClass("cn.piq.sfchome.client.SfcStartupProgressTest"),selectClass("cn.piq.sfchome.server.SfcHomeStartupSourceContractTest")).build();var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));if(listener.getSummary().getTestsFoundCount()!=21||listener.getSummary().getTestsFailedCount()!=0||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("Home startup regression failure");}
}
