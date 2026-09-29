import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
public final class CabinetSelectionTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(selectClass("cn.piq.fcarcade.cabinet.CabinetConfigureIntentTest"),selectClass("cn.piq.fcarcade.cabinet.CabinetRomBindingsTest")).build();
        var summary=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(summary);launcher.execute(request);var result=summary.getSummary();
        result.printFailuresTo(new java.io.PrintWriter(System.err,true));
        if(result.getTestsFoundCount()!=13||result.getTestsFailedCount()!=0||result.getTestsSucceededCount()<12||result.getTestsAbortedCount()>1)throw new AssertionError("Selection suite failed");
        System.out.println("{\"found\":"+result.getTestsFoundCount()+",\"passed\":"+result.getTestsSucceededCount()+",\"aborted\":"+result.getTestsAbortedCount()+",\"failed\":"+result.getTestsFailedCount()+"}");
    }
}
