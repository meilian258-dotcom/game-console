import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class CabinetClientReviewRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.client.cabinet.CabinetCleanupTest"),
                selectClass("cn.piq.fcarcade.client.cabinet.CabinetMenuLayoutTest"),
                selectClass("cn.piq.fcarcade.client.cabinet.CabinetClientSafetyTest"),
                selectClass("cn.piq.fcarcade.client.cabinet.CabinetKeysTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=16||listener.getSummary().getTestsFailedCount()!=0||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("Client cabinet review tests failed");
    }
}
