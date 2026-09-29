import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** New count is explicit; the historical 25-test runner remains unchanged. */
public final class CabinetGameDedup35Runner {
    public static void main(String[] args) {
        int expected=Integer.parseInt(args[0]);
        if(expected<25||args.length<4)throw new AssertionError("Missing expected game safety suite");
        var request=LauncherDiscoveryRequestBuilder.request();
        for(int i=1;i<args.length;i++)request.selectors(selectClass(args[i]));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request.build());
        var result=listener.getSummary();result.printFailuresTo(new java.io.PrintWriter(System.err,true));
        if(result.getTestsFoundCount()!=expected||result.getTestsSucceededCount()!=expected
                ||result.getTestsFailedCount()!=0||result.getTestsSkippedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("Game safety JUnit failure/count/skip: expected "+expected+", found "+result.getTestsFoundCount()+", succeeded "+result.getTestsSucceededCount());
        System.out.println("{\"ok\":true,\"tests\":"+expected+",\"passed\":"+result.getTestsSucceededCount()+",\"skipped\":0,\"actual_junit_lifecycle\":true}");
    }
}
