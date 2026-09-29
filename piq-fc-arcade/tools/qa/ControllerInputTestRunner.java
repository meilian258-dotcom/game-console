import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class ControllerInputTestRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.session.ControllerInputTransitionsTest"),
                selectClass("cn.piq.fcarcade.session.LockstepStateTest"),
                selectClass("cn.piq.fcarcade.session.LockstepTimelineTest"),
                selectClass("cn.piq.fcarcade.home.HomeControllerLedgerTest"),
                selectClass("cn.piq.fcarcade.client.ControllerFramePresentationTest"),
                selectClass("cn.piq.fcarcade.client.ControllerInputCaptureTest"),
                selectClass("cn.piq.fcarcade.client.ClientNesWorkerTest")).build();
        var listener = new SummaryGeneratingListener(); var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener); launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if (listener.getSummary().getTestsFoundCount() < 30 || listener.getSummary().getTestsFailedCount() != 0)
            throw new AssertionError("Controller input tests did not pass");
    }
}
