import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class CabinetMediaFallbackTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.fcarcade.cabinet.CabinetMediaCodecTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=19||result.getTestsSucceededCount()!=19||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("Media codec fallback regression failed");
        System.out.println("{\"ok\":true,\"tests\":19,\"minecraft_or_native_core_started\":false}");
    }
}
