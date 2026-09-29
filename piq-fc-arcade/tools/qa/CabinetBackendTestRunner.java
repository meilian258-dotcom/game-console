import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Pure ownership and static wiring checks. Deliberately does not launch Minecraft or an emulator. */
public final class CabinetBackendTestRunner {
    public static void main(String[] args) {
        var request=LauncherDiscoveryRequestBuilder.request().selectors(
                selectClass("cn.piq.fcarcade.cabinet.CabinetLeaseLedgerTest"),
                selectClass("cn.piq.fcarcade.cabinet.CabinetSourceContractTest")).build();
        var listener=new SummaryGeneratingListener();var launcher=LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out));
        if(listener.getSummary().getTestsFoundCount()!=14||listener.getSummary().getTestsFailedCount()!=0
                ||listener.getSummary().getTestsSucceededCount()!=14)throw new AssertionError("Cabinet backend tests failed");
    }
}
