import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
/** Pure boundary/protocol tests: never starts Minecraft, WASM or native helper. */
public final class FcHomeHosted39Tests {
    public static void main(String[] args){var request=LauncherDiscoveryRequestBuilder.request().selectors(
        DiscoverySelectors.selectClass("cn.piq.fcarcade.server.FcHomeHostedNetwork39Test"),
        DiscoverySelectors.selectClass("cn.piq.fcarcade.server.hosted.NesManagedState39Test")).build();
        var summary=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(summary);launcher.execute(request);
        summary.getSummary().printTo(new java.io.PrintWriter(System.out));summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(summary.getSummary().getTestsFoundCount()!=11||summary.getSummary().getTotalFailureCount()!=0)throw new AssertionError("FC home hosted boundaries failed");
    }
}
