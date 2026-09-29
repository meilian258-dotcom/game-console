import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Isolated source behavior tests only; not Minecraft/native or final-JAR evidence. */
public final class SnapshotSyncClientTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request();for(String name:args)request.selectors(selectClass(name));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request.build());
        var summary=listener.getSummary();summary.printTo(new java.io.PrintWriter(System.out));summary.printFailuresTo(new java.io.PrintWriter(System.out));
        if(summary.getTestsFoundCount()<20||summary.getTestsFailedCount()!=0||summary.getTestsAbortedCount()!=0||summary.getTestsSucceededCount()!=summary.getTestsFoundCount())throw new AssertionError("Snapshot sync client regression failed");
        System.out.println("{\"ok\":true,\"mode\":\"source-isolated\",\"minecraft_or_native_started\":false,\"tests\":"+summary.getTestsSucceededCount()+"}");
    }
}
