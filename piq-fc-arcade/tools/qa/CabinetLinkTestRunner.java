import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public final class CabinetLinkTestRunner {
    public static void main(String[] args){
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.cabinet.CabinetLinkLedgerTest"),
                selectClass("cn.piq.fcarcade.cabinet.CabinetLinksSourceContractTest")).build();
        var summary=new SummaryGeneratingListener();var launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(summary);launcher.execute(request);
        var result=summary.getSummary();result.printTo(new java.io.PrintWriter(System.out,true));result.printFailuresTo(new java.io.PrintWriter(System.out,true));
        if(result.getTestsFoundCount()!=16||result.getTestsFailedCount()!=0||result.getTestsSkippedCount()!=0)throw new AssertionError("Link tests failed");
        System.out.println("{\"passed\":true,\"tests\":"+result.getTestsFoundCount()+",\"failed\":0,\"minecraft_started\":false}");
    }
}
