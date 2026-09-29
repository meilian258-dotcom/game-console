import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** New hosted boundary tests only. Does not start Minecraft, a WASM core or a native helper. */
public final class ServerHosted39Tests {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            DiscoverySelectors.selectClass("cn.piq.fcarcade.server.hosted.ServerCoreWorkerTest"),
            DiscoverySelectors.selectClass("cn.piq.fcarcade.server.hosted.HostedSaveFileTest"),
            DiscoverySelectors.selectClass("cn.piq.fcarcade.server.hosted.NesHostedAudioTest"),
            DiscoverySelectors.selectClass("cn.piq.fcarcade.server.hosted.ServerCoreRegistryTest")).build();
        var summary=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(summary);launcher.execute(request);
        summary.getSummary().printTo(new java.io.PrintWriter(System.out));summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(summary.getSummary().getTestsFoundCount()<20||summary.getSummary().getTotalFailureCount()!=0)throw new AssertionError("Hosted boundary regression failed");
    }
}
