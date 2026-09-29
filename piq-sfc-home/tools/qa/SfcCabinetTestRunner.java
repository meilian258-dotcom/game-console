import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class SfcCabinetTestRunner {
    public static void main(String[]args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("cn.piq.sfchome.client.SfcCoreLeaseTest"),
            selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetInputsTest"),
            selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetFramesTest"),
            selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetRomTest"),
            selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetSessionTest"),
            selectClass("cn.piq.sfchome.client.cabinet.SfcCabinetSourceContractTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=24||listener.getSummary().getTestsFailedCount()!=0
                ||listener.getSummary().getTestsSkippedCount()!=0)throw new AssertionError("Cabinet adapter regression failure");
    }
}
