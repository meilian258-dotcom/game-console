import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Pure production FIFO behavior; no Minecraft, helper process or native core. */
public final class NativeInputPortsTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.nativearcade.bridge.NativeInputPortsTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=10||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0)
            throw new AssertionError("Four-port input FIFO regression failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsFoundCount()+",\"minecraft_or_native_core_started\":false}");
    }
}
