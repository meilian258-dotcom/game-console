import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class CabinetGameTestRunner {
    public static void main(String[] args) {
        var builder=LauncherDiscoveryRequestBuilder.request();for(String name:args)builder.selectors(selectClass(name));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(builder.build());
        var result=listener.getSummary();result.printFailuresTo(new java.io.PrintWriter(System.err,true));
        if(result.getTestsFailedCount()!=0||result.getTestsFoundCount()!=25)throw new AssertionError("Game safety JUnit failure/count");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsFoundCount()+",\"passed\":"+result.getTestsSucceededCount()+",\"skipped\":"+(result.getTestsSkippedCount()+result.getTestsAbortedCount())+",\"actual_junit_lifecycle\":true}");
    }
}
