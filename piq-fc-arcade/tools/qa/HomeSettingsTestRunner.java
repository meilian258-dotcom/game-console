import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Targeted Java-only regression launcher; callers provide production and test classpaths. */
public final class HomeSettingsTestRunner {
    public static void main(String[] args) {
        if(args.length==0)throw new IllegalArgumentException("Provide fully qualified test classes");
        var request=LauncherDiscoveryRequestBuilder.request();
        for(String name:args)request.selectors(selectClass(name));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request.build());
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()==0||result.getTestsSucceededCount()!=result.getTestsFoundCount()||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("Home settings regression failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsSucceededCount()+",\"native_cores_started\":false}");
    }
}
