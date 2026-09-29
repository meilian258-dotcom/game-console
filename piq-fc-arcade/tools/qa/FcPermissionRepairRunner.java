import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class FcPermissionRepairRunner {
    public static void main(String[] names) {
        var request=LauncherDiscoveryRequestBuilder.request();for(String name:names)request.selectors(selectClass(name));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request.build());
        var summary=listener.getSummary();summary.printFailuresTo(new java.io.PrintWriter(System.err,true));
        if(summary.getTestsFoundCount()<20||summary.getTestsFailedCount()!=0||summary.getTestsSkippedCount()!=0||summary.getTestsAbortedCount()!=0)throw new AssertionError("Permission/save regression failure");
        System.out.println("{\"ok\":true,\"tests\":"+summary.getTestsFoundCount()+",\"passed\":"+summary.getTestsSucceededCount()+",\"actual_junit_lifecycle\":true}");
    }
}
