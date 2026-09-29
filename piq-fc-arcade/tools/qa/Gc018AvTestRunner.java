import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Standalone, no Minecraft bootstrap or Gradle output mutation. */
public final class Gc018AvTestRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.home.TelevisionPowerTransitionTest"),
                selectClass("cn.piq.fcarcade.home.EmptyConsolePowerTest"),
                selectClass("cn.piq.fcarcade.home.HomePresentationSourceContractTest")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out, true));
        summary.printFailuresTo(new java.io.PrintWriter(System.out, true));
        if (summary.getTestsFoundCount() != 20 || summary.getTestsFailedCount() != 0)
            throw new AssertionError("GC-018 AV tests failed");
    }
}
