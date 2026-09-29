import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class CartridgeSaveModeTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.fcarcade.home.CartridgeSaveModeSettingTest"),selectClass("cn.piq.fcarcade.server.ServerRomSaveModePolicyTest"),selectClass("cn.piq.fcarcade.client.ui.CartridgeWorkbenchLayoutTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=13||result.getTestsFailedCount()!=0||result.getTestsAbortedCount()!=0||result.getTestsSkippedCount()!=0)throw new AssertionError("Save mode tests failed");
        System.out.println("{\"ok\":true,\"tests\":"+result.getTestsFoundCount()+",\"real_metadata_file_roundtrip\":true,\"minecraft_started\":false}");
    }
}
