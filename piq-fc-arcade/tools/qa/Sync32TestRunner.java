import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class Sync32TestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request();for(String name:args)request.selectors(selectClass(name));
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request.build());
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()<20||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0||result.getTestsSucceededCount()!=result.getTestsFoundCount())throw new AssertionError("Sync32 final regression failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsSucceededCount()+",\"production_origin\":\"final-jar-only\"}");
    }
}
