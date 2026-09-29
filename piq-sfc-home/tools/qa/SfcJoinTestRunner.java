import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcJoinTestRunner {
    public static void main(String[]args){var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.sfchome.server.SfcJoinGateTest"),selectClass("cn.piq.sfchome.server.SfcJoinSourceTest"),selectClass("cn.piq.sfchome.client.SfcSharedDeviceLayoutTest"),selectClass("cn.piq.sfchome.server.SfcInputHealthTest")).build();var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));if(listener.getSummary().getTestsFoundCount()!=24||listener.getSummary().getTestsFailedCount()!=0)throw new AssertionError("Join/UI tests failed");}
}
