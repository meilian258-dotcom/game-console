import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
/** Pure tests only; source-contract working directory is piq-sfc-home. */
public final class CabinetMediaTestRunner{
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.cabinet.CabinetMediaCodecTest"),
                selectClass("cn.piq.fcarcade.cabinet.CabinetRetroAdapterTest"),
                selectClass("cn.piq.retro.api.RetroMultiplayerCompatibilityTest"),
                selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetPortReleaseTest"),
                selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetInputsTest"),
                selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetSessionTest"),
                selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetSourceContractTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        var result=listener.getSummary();result.printTo(new java.io.PrintWriter(System.out));result.printFailuresTo(new java.io.PrintWriter(System.out));
        if(result.getTestsFoundCount()!=47||result.getTestsSucceededCount()!=47||result.getTestsFailedCount()!=0||result.getTestsSkippedCount()!=0)
            throw new AssertionError("Cabinet media/ports regression failure");
        System.out.println("{\"passed\":true,\"tests\":47,\"minecraft_started\":false}");
    }
}
