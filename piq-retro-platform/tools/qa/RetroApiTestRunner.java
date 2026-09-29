import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Offline API-only suite; no Minecraft, emulator or Gradle process is started. */
public final class RetroApiTestRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.retro.api.RetroFrameTest"),
                selectClass("cn.piq.retro.api.RetroRegistryTest")).build();
        var summary = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(summary);
        launcher.execute(request);
        var output = new java.io.PrintWriter(System.out, true);
        summary.getSummary().printTo(output);
        summary.getSummary().printFailuresTo(output);
        if (summary.getSummary().getTestsFoundCount() != 10
                || summary.getSummary().getTestsSucceededCount() != 10
                || summary.getSummary().getTestsFailedCount() != 0
                || summary.getSummary().getTestsSkippedCount() != 0) {
            throw new AssertionError("Retro API regression failed");
        }
    }
}
