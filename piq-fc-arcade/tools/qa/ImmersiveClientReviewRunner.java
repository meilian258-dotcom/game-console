import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Standalone JUnit launcher; compile only the pure owner/input and test classes, never Minecraft. */
public final class ImmersiveClientReviewRunner {
    public static void main(String[] args) {
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.client.cabinet.ImmersiveClientSafetyTest"),
                selectClass("cn.piq.fcarcade.client.cabinet.CabinetImmersiveInputTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=20||listener.getSummary().getTestsFailedCount()!=0
                ||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("Immersive client safety checks failed");
    }
}
